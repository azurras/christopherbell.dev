package dev.christopherbell.configuration.persistence.migration;

import com.mongodb.client.MongoClient;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Production write exclusion: deployment lock, stopped website writer, and Mongo fsync lock. */
final class ProductionFinalizationFreezeGuard implements FinalizationFreezeGuard {
  private static final Path DEPLOYMENT_LOCK = Path.of(
      "C:\\ProgramData\\christopherbell.dev\\locks\\deploy.lock");
  private static final String WEBSITE_SERVICE = "ChristopherBellDev";
  private static final Map<Path, ProductionFinalizationFreezeGuard> FAILED_ACQUISITIONS =
      new HashMap<>();

  private final FileChannel deploymentChannel;
  private final FileLock deploymentLock;
  private final BooleanSupplier websiteStopped;
  private final MongoFinalizationFreezeGuard mongoGuard;
  private boolean mongoReleased;
  private boolean deploymentLockReleased;
  private boolean deploymentChannelClosed;
  private boolean open = true;

  private ProductionFinalizationFreezeGuard(
      FileChannel deploymentChannel,
      FileLock deploymentLock,
      BooleanSupplier websiteStopped,
      MongoFinalizationFreezeGuard mongoGuard) {
    this.deploymentChannel = deploymentChannel;
    this.deploymentLock = deploymentLock;
    this.websiteStopped = websiteStopped;
    this.mongoGuard = mongoGuard;
  }

  static ProductionFinalizationFreezeGuard acquire(MongoClient client) {
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
        .contains("win")) {
      throw new MigrationStorageException(
          "Production PostgreSQL finalization is supported only on Windows.");
    }
    FinalizeEvidenceLoader.requireTrustedProductionDirectory(DEPLOYMENT_LOCK.getParent());
    return acquire(client, DEPLOYMENT_LOCK,
        () -> windowsServiceStopped(WEBSITE_SERVICE, Duration.ofSeconds(15)));
  }

  static ProductionFinalizationFreezeGuard acquireForTest(
      MongoClient client, Path deploymentLock, BooleanSupplier websiteStopped) {
    return acquire(client, deploymentLock, websiteStopped);
  }

  private static ProductionFinalizationFreezeGuard acquire(
      MongoClient client, Path path, BooleanSupplier websiteStopped) {
    Objects.requireNonNull(client, "client");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(websiteStopped, "websiteStopped");
    var lockPath = path.toAbsolutePath().normalize();
    recoverFailedAcquisition(lockPath);
    FileChannel channel = null;
    FileLock lock = null;
    try {
      channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
          StandardOpenOption.READ, StandardOpenOption.WRITE);
      lock = channel.tryLock();
      if (lock == null) {
        throw new MigrationStorageException(
            "Production deployment lock is already held.");
      }
      requireWebsiteStopped(websiteStopped);
      var mongo = MongoFinalizationFreezeGuard.prepare(client);
      var guard = new ProductionFinalizationFreezeGuard(channel, lock, websiteStopped, mongo);
      channel = null;
      lock = null;
      try {
        mongo.acquirePrepared();
        return guard;
      } catch (RuntimeException failure) {
        closeOrRetainFailedAcquisition(lockPath, guard, failure);
        throw failure;
      }
    } catch (RuntimeException | IOException failure) {
      closeAfterFailedAcquire(lock, channel, failure);
      if (failure instanceof MigrationStorageException storage) {
        throw storage;
      }
      throw new MigrationStorageException(
          "Production finalization write freeze could not be acquired.", failure);
    }
  }

  private static void recoverFailedAcquisition(Path path) {
    synchronized (FAILED_ACQUISITIONS) {
      var failedGuard = FAILED_ACQUISITIONS.get(path);
      if (failedGuard == null) {
        return;
      }
      try {
        failedGuard.close();
        FAILED_ACQUISITIONS.remove(path);
      } catch (RuntimeException recoveryFailure) {
        throw new MigrationStorageException(
            "Previous production finalization write freeze could not be recovered.",
            recoveryFailure);
      }
    }
  }

  private static void closeOrRetainFailedAcquisition(
      Path path, ProductionFinalizationFreezeGuard guard, RuntimeException failure) {
    try {
      guard.close();
    } catch (RuntimeException closeFailure) {
      failure.addSuppressed(closeFailure);
      synchronized (FAILED_ACQUISITIONS) {
        var displaced = FAILED_ACQUISITIONS.putIfAbsent(path, guard);
        if (displaced != null && displaced != guard) {
          throw new IllegalStateException(
              "A production finalization cleanup handle is already retained for " + path + ".");
        }
      }
    }
  }

  @Override
  public void requireLocked() {
    if (!open || !deploymentLock.isValid()) {
      throw new MigrationStorageException("Production deployment lock is not held.");
    }
    requireWebsiteStopped(websiteStopped);
    mongoGuard.requireLocked();
  }

  @Override
  public synchronized void close() {
    if (!open) {
      return;
    }
    if (!mongoReleased) {
      mongoGuard.close();
      mongoReleased = true;
    }
    RuntimeException failure = null;
    if (!deploymentLockReleased) {
      try {
        deploymentLock.release();
        deploymentLockReleased = true;
      } catch (IOException closeFailure) {
        failure = new MigrationStorageException(
            "Production deployment lock could not be released.", closeFailure);
      }
    }
    if (failure == null && !deploymentChannelClosed) {
      try {
        deploymentChannel.close();
        deploymentChannelClosed = true;
      } catch (IOException closeFailure) {
        failure = new MigrationStorageException(
            "Production deployment lock could not be released.", closeFailure);
      }
    }
    if (failure == null) {
      open = false;
      return;
    }
    throw failure;
  }

  private static void requireWebsiteStopped(BooleanSupplier websiteStopped) {
    if (!websiteStopped.getAsBoolean()) {
      throw new MigrationStorageException(
          "Website writer is not stopped for PostgreSQL finalization.");
    }
  }

  private static boolean windowsServiceStopped(String serviceName, Duration timeout) {
    try {
      var process = new ProcessBuilder("sc.exe", "query", serviceName)
          .redirectErrorStream(true)
          .start();
      var completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        return false;
      }
      var output = new String(process.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      return process.exitValue() == 0
          && output.matches("(?s).*STATE\\s*:\\s*1\\s+STOPPED.*");
    } catch (IOException | InterruptedException failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  private static void closeAfterFailedAcquire(
      FileLock lock,
      FileChannel channel,
      Throwable failure) {
    try {
      if (lock != null && lock.isValid()) {
        lock.release();
      }
      if (channel != null) {
        channel.close();
      }
    } catch (RuntimeException | IOException closeFailure) {
      failure.addSuppressed(closeFailure);
    }
  }
}

package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.SharedFolderProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns the fixed cross-process lock that serializes maintenance effects on one host. */
@Component
public final class SharedFolderMaintenanceHostLock {
  private static final String LOCK_FILE_NAME = "shared-folder-maintenance.lock";
  private static final String INVALID_ROOT_MESSAGE =
      "Shared-folder maintenance host lock root is invalid";
  private static final String UNAVAILABLE_MESSAGE =
      "Shared-folder maintenance host lock is unavailable";

  private final Path systemRoot;
  private final Path lockPath;

  @Autowired
  public SharedFolderMaintenanceHostLock(SharedFolderProperties properties) {
    this(properties == null ? null : properties.systemRoot());
  }

  public SharedFolderMaintenanceHostLock(Path systemRoot) {
    if (systemRoot == null) throw new IllegalArgumentException(INVALID_ROOT_MESSAGE);
    try {
      this.systemRoot = systemRoot.toAbsolutePath().normalize();
      this.lockPath = this.systemRoot.resolve(LOCK_FILE_NAME).normalize();
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException(INVALID_ROOT_MESSAGE);
    }
    if (!lockPath.startsWith(this.systemRoot) || !this.systemRoot.equals(lockPath.getParent())) {
      throw new IllegalArgumentException(INVALID_ROOT_MESSAGE);
    }
  }

  /** Attempts the host-wide lock without waiting; absence means another process owns it. */
  public Optional<Handle> tryAcquire() {
    try {
      if (!Files.isDirectory(systemRoot, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
    } catch (RuntimeException failure) {
      throw unavailable();
    }
    FileChannel channel;
    try {
      channel = FileChannel.open(
          lockPath,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
    try {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        closeAfterFailedAcquisition(channel);
        return Optional.empty();
      }
      return Optional.of(new Handle(lock, channel));
    } catch (OverlappingFileLockException contention) {
      closeAfterFailedAcquisition(channel);
      return Optional.empty();
    } catch (IOException | RuntimeException failure) {
      closeAfterFailedAcquisition(channel);
      throw unavailable();
    }
  }

  private static void closeAfterFailedAcquisition(FileChannel channel) {
    try {
      channel.close();
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(UNAVAILABLE_MESSAGE);
  }

  /** Exclusive ownership handle for both native lock and its required open channel. */
  public static final class Handle implements AutoCloseable {
    private final FileLock lock;
    private final FileChannel channel;
    private boolean closed;

    private Handle(FileLock lock, FileChannel channel) {
      this.lock = lock;
      this.channel = channel;
    }

    @Override
    public synchronized void close() {
      if (closed) return;
      closed = true;
      boolean failed = false;
      try {
        lock.release();
      } catch (IOException | RuntimeException failure) {
        failed = true;
      } finally {
        try {
          channel.close();
        } catch (IOException | RuntimeException failure) {
          failed = true;
        }
      }
      if (failed) throw unavailable();
    }
  }
}

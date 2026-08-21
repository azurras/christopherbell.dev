package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.bson.Document;

class ProductionFinalizationFreezeGuardTest {
  @Test
  @EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
  void holdsDeploymentLockAndFailsClosedIfWebsiteWriterStopsBeingQuiescent(
      @TempDir Path directory) {
    var serviceStopped = new AtomicBoolean(true);
    var lockPath = directory.resolve("deploy.lock");
    try (var mongo = MongoClients.create(System.getenv("MONGODB_MIGRATION_TEST_URI"));
        var guard = ProductionFinalizationFreezeGuard.acquireForTest(
            mongo, lockPath, serviceStopped::get)) {
      guard.requireLocked();
      try (var contender = FileChannel.open(
          lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
          StandardOpenOption.WRITE)) {
        assertThatThrownBy(contender::tryLock)
            .isInstanceOf(OverlappingFileLockException.class);
      } catch (java.io.IOException failure) {
        throw new AssertionError(failure);
      }

      serviceStopped.set(false);
      assertThatThrownBy(guard::requireLocked)
          .isInstanceOf(MigrationStorageException.class)
          .hasMessage("Website writer is not stopped for PostgreSQL finalization.");
    }
  }

  @Test
  void keepsDeploymentLockHeldUntilNestedMongoUnlockCanBeRetried(@TempDir Path directory)
      throws Exception {
    var client = mock(MongoClient.class);
    var admin = mock(MongoDatabase.class);
    when(client.getDatabase("admin")).thenReturn(admin);
    when(admin.runCommand(any(Document.class)))
        .thenReturn(new Document("ok", 1))
        .thenReturn(new Document("fsyncLock", true))
        .thenReturn(new Document("fsyncLock", true))
        .thenThrow(new IllegalStateException("unlock response timed out"))
        .thenReturn(new Document("fsyncLock", true))
        .thenReturn(new Document("ok", 1));
    var lockPath = directory.resolve("deploy.lock");
    var guard = ProductionFinalizationFreezeGuard.acquireForTest(client, lockPath, () -> true);

    assertThatThrownBy(guard::close)
        .isInstanceOf(MigrationStorageException.class)
        .hasMessage("MongoDB finalization write freeze could not be released.");
    try (var contender = FileChannel.open(
        lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE)) {
      assertThatThrownBy(contender::tryLock)
          .isInstanceOf(OverlappingFileLockException.class);
    }

    guard.close();
    try (var contender = FileChannel.open(
        lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE);
        var released = contender.tryLock()) {
      // Acquiring the lock proves the production wrapper released it only after Mongo unlock.
    }
  }

  @Test
  void retainsDeploymentLockAfterAmbiguousMongoAcquisitionUntilRecoverySucceeds(
      @TempDir Path directory) throws Exception {
    var client = mock(MongoClient.class);
    var admin = mock(MongoDatabase.class);
    var commandFailure = new IllegalStateException("fsync response timed out");
    var directUnlockFailure = new IllegalStateException("direct unlock response timed out");
    when(client.getDatabase("admin")).thenReturn(admin);
    when(admin.runCommand(any(Document.class)))
        .thenThrow(commandFailure)
        .thenThrow(directUnlockFailure)
        .thenThrow(new IllegalStateException("cleanup unlock response timed out"))
        .thenReturn(new Document("ok", 1))
        .thenReturn(new Document("ok", 1))
        .thenReturn(new Document("fsyncLock", true))
        .thenReturn(new Document("fsyncLock", true))
        .thenReturn(new Document("fsyncLock", true))
        .thenReturn(new Document("ok", 1));
    var lockPath = directory.resolve("deploy.lock");

    var failure = catchThrowableOfType(
        () -> ProductionFinalizationFreezeGuard.acquireForTest(client, lockPath, () -> true),
        MigrationStorageException.class);

    assertThat(failure.getCause()).isSameAs(commandFailure);
    assertThat(commandFailure.getSuppressed()).containsExactly(directUnlockFailure);
    try (var contender = FileChannel.open(
        lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE)) {
      assertThatThrownBy(contender::tryLock)
          .isInstanceOf(OverlappingFileLockException.class);
    }
    assertThat(failure.getSuppressed()).hasSize(1);

    try (var recovered = ProductionFinalizationFreezeGuard.acquireForTest(
        client, lockPath, () -> true)) {
      recovered.requireLocked();
    }
    try (var contender = FileChannel.open(
        lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
        StandardOpenOption.WRITE);
        var released = contender.tryLock()) {
      assertThat(released).isNotNull();
    }
  }
}

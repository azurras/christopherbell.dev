package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class MongoFinalizationFreezeGuardTest {
  @Test
  void releasesTheServerLockWhenAcquisitionVerificationFails() {
    var client = mock(MongoClient.class);
    var admin = mock(MongoDatabase.class);
    when(client.getDatabase("admin")).thenReturn(admin);
    when(admin.runCommand(any(Document.class)))
        .thenReturn(new Document("ok", 1))
        .thenThrow(new IllegalStateException("verification failed"))
        .thenReturn(new Document("ok", 1));

    assertThatThrownBy(() -> MongoFinalizationFreezeGuard.acquire(client))
        .isInstanceOf(MigrationStorageException.class);

    verify(admin, times(3)).runCommand(any(Document.class));
  }

  @Test
  void directlyUnlocksWhenTheInitialFsyncCommandOutcomeIsAmbiguous() {
    var client = mock(MongoClient.class);
    var admin = mock(MongoDatabase.class);
    var commandFailure = new IllegalStateException("fsync response timed out");
    when(client.getDatabase("admin")).thenReturn(admin);
    when(admin.runCommand(any(Document.class)))
        .thenThrow(commandFailure)
        .thenReturn(new Document("ok", 1));

    assertThatThrownBy(() -> MongoFinalizationFreezeGuard.acquire(client))
        .isInstanceOf(MigrationStorageException.class)
        .hasCause(commandFailure);

    verify(admin, times(2)).runCommand(any(Document.class));
  }

  @Test
  void preservesAmbiguousFsyncAndDirectUnlockFailures() {
    var client = mock(MongoClient.class);
    var admin = mock(MongoDatabase.class);
    var commandFailure = new IllegalStateException("fsync response timed out");
    var unlockFailure = new IllegalStateException("fsyncUnlock response timed out");
    when(client.getDatabase("admin")).thenReturn(admin);
    when(admin.runCommand(any(Document.class)))
        .thenThrow(commandFailure)
        .thenThrow(unlockFailure);

    assertThatThrownBy(() -> MongoFinalizationFreezeGuard.acquire(client))
        .isInstanceOf(MigrationStorageException.class)
        .hasCause(commandFailure)
        .satisfies(failure -> assertThat(failure.getCause().getSuppressed())
            .containsExactly(unlockFailure));

    verify(admin, times(2)).runCommand(any(Document.class));
  }

  @Test
  void directlyUnlocksAfterCloseVerificationFails() {
    var client = mock(MongoClient.class);
    var admin = mock(MongoDatabase.class);
    when(client.getDatabase("admin")).thenReturn(admin);
    when(admin.runCommand(any(Document.class)))
        .thenReturn(new Document("ok", 1))
        .thenReturn(new Document("fsyncLock", true))
        .thenThrow(new IllegalStateException("verification failed"))
        .thenReturn(new Document("ok", 1));
    var guard = MongoFinalizationFreezeGuard.acquire(client);

    assertThatThrownBy(guard::close)
        .isInstanceOf(MigrationStorageException.class)
        .hasMessage("MongoDB finalization write freeze could not be released.");

    verify(admin, times(4)).runCommand(any(Document.class));
  }

  @Test
  void preservesVerificationAndUnlockFailuresAndAllowsUnlockRetry() {
    var client = mock(MongoClient.class);
    var admin = mock(MongoDatabase.class);
    when(client.getDatabase("admin")).thenReturn(admin);
    when(admin.runCommand(any(Document.class)))
        .thenReturn(new Document("ok", 1))
        .thenReturn(new Document("fsyncLock", true))
        .thenThrow(new IllegalStateException("verification failed"))
        .thenThrow(new IllegalStateException("unlock failed"))
        .thenReturn(new Document("fsyncLock", true))
        .thenReturn(new Document("ok", 1));
    var guard = MongoFinalizationFreezeGuard.acquire(client);

    assertThatThrownBy(guard::close)
        .isInstanceOf(MigrationStorageException.class)
        .hasCauseInstanceOf(MigrationStorageException.class)
        .satisfies(failure -> assertThat(failure.getCause().getSuppressed()).hasSize(1));

    guard.close();
    guard.close();
    verify(admin, times(6)).runCommand(any(Document.class));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
  void blocksAnIndependentWriterUntilTheGuardIsReleased() throws Exception {
    var uri = requireDisposableTestUri();
    var collectionName = "migration_finalize_freeze_probe";
    try (var administrativeClient = MongoClients.create(uri);
        var independentWriter = MongoClients.create(uri);
        var executor = Executors.newSingleThreadExecutor()) {
      var collection = independentWriter.getDatabase("test").getCollection(collectionName);
      collection.deleteMany(new Document());

      try (var guard = MongoFinalizationFreezeGuard.acquire(administrativeClient)) {
        guard.requireLocked();
        var write = executor.submit(() -> {
          collection.insertOne(new Document("probe", "after-commit"));
          return null;
        });

        assertThatThrownBy(() -> write.get(Duration.ofMillis(400).toMillis(), TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        assertThat(collection.countDocuments()).isZero();

        guard.close();
        write.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        assertThat(collection.countDocuments()).isEqualTo(1);
      } finally {
        collection.deleteMany(new Document());
      }
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
  void failsClosedWhenAnotherAdministratorWithdrawsTheServerLock() throws Exception {
    var uri = requireDisposableTestUri();
    try (var administrativeClient = MongoClients.create(uri);
        var independentAdministrator = MongoClients.create(uri)) {
      var guard = MongoFinalizationFreezeGuard.acquire(administrativeClient);
      independentAdministrator.getDatabase("admin").runCommand(new Document("fsyncUnlock", 1));

      assertThatThrownBy(guard::requireLocked)
          .isInstanceOf(MigrationStorageException.class)
          .hasMessage("MongoDB finalization write freeze is not held.");
      assertThatThrownBy(guard::close)
          .isInstanceOf(MigrationStorageException.class)
          .hasMessage("MongoDB finalization write freeze could not be released.")
          .satisfies(failure -> assertThat(failure.getCause().getSuppressed()).hasSize(1));
      guard.close();
    }
  }

  private static String requireDisposableTestUri() {
    var uri = System.getenv("MONGODB_MIGRATION_TEST_URI");
    assertThat(uri).matches("mongodb://127\\.0\\.0\\.1:(?!27017)[0-9]+/test");
    return uri;
  }
}

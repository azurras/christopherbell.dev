package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.client.MongoClients;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "MONGODB_MIGRATION_TEST_URI", matches = ".+")
class ProductionFinalizationFreezeGuardTest {
  @Test
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
}

package dev.christopherbell.configuration.persistence.migration;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;

/**
 * Holds MongoDB's server-wide {@code fsyncLock} while PostgreSQL finalization is in flight.
 *
 * <p>The configured MongoDB principal must be authorized to run {@code fsync}, {@code fsyncUnlock},
 * and {@code currentOp} against {@code admin}. The lock permits reads and blocks writes server-wide;
 * callers must close this guard in {@code finally}. MongoDB intentionally retains an fsync lock if
 * the client disconnects, so operational recovery is an authenticated {@code fsyncUnlock} after
 * confirming that PostgreSQL finalization is not running.
 */
final class MongoFinalizationFreezeGuard implements FinalizationFreezeGuard {
  private static final String NOT_HELD = "MongoDB finalization write freeze is not held.";

  private final MongoClient client;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private volatile Ownership ownership = Ownership.PREPARED;

  private MongoFinalizationFreezeGuard(MongoClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  static MongoFinalizationFreezeGuard acquire(MongoClient client) {
    var guard = prepare(client);
    guard.acquirePrepared();
    return guard;
  }

  static MongoFinalizationFreezeGuard prepare(MongoClient client) {
    return new MongoFinalizationFreezeGuard(client);
  }

  synchronized void acquirePrepared() {
    if (ownership != Ownership.PREPARED) {
      throw new IllegalStateException("MongoDB finalization guard was already acquired.");
    }
    ownership = Ownership.UNCERTAIN;
    try {
      client.getDatabase("admin").runCommand(new Document("fsync", 1).append("lock", true));
      ownership = Ownership.LOCKED;
      requireLocked();
    } catch (RuntimeException exception) {
      try {
        unlockDirectly();
      } catch (RuntimeException releaseFailure) {
        exception.addSuppressed(releaseFailure);
      }
      throw new MigrationStorageException("MongoDB finalization write freeze could not be acquired.",
          exception);
    }
  }

  public void requireLocked() {
    if (!open.get() || ownership != Ownership.LOCKED || !serverReportsLocked()) {
      throw new MigrationStorageException(NOT_HELD);
    }
  }

  @Override
  public synchronized void close() {
    if (!open.get()) {
      return;
    }
    if (ownership == Ownership.PREPARED) {
      open.set(false);
      ownership = Ownership.RELEASED;
      return;
    }
    RuntimeException verificationFailure = null;
    if (ownership == Ownership.LOCKED) {
      try {
        if (!serverReportsLocked()) {
          verificationFailure = new MigrationStorageException(NOT_HELD);
        }
      } catch (RuntimeException exception) {
        verificationFailure = exception;
      }
    }
    RuntimeException unlockFailure = null;
    try {
      unlockDirectly();
    } catch (RuntimeException exception) {
      unlockFailure = exception;
    }
    if (verificationFailure != null) {
      if (unlockFailure != null) {
        verificationFailure.addSuppressed(unlockFailure);
      }
      throw new MigrationStorageException(
          "MongoDB finalization write freeze could not be released.", verificationFailure);
    }
    if (unlockFailure != null) {
      throw new MigrationStorageException(
          "MongoDB finalization write freeze could not be released.", unlockFailure);
    }
  }

  private boolean serverReportsLocked() {
    try {
      var status = client.getDatabase("admin").runCommand(new Document("currentOp", 1));
      return Boolean.TRUE.equals(status.getBoolean("fsyncLock"));
    } catch (RuntimeException exception) {
      throw new MigrationStorageException("MongoDB finalization write freeze could not be verified.",
          exception);
    }
  }

  private void unlockDirectly() {
    try {
      client.getDatabase("admin").runCommand(new Document("fsyncUnlock", 1));
      ownership = Ownership.RELEASED;
      open.set(false);
    } catch (RuntimeException failure) {
      if (alreadyUnlocked(failure)) {
        ownership = Ownership.RELEASED;
        open.set(false);
        return;
      }
      throw failure;
    }
  }

  private static boolean alreadyUnlocked(RuntimeException failure) {
    return failure instanceof MongoCommandException commandFailure
        && commandFailure.getErrorCode() == 20;
  }

  private enum Ownership {
    PREPARED,
    UNCERTAIN,
    LOCKED,
    RELEASED
  }
}

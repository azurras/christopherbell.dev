package dev.christopherbell.configuration.persistence.migration;

public final class MigrationStorageException extends RuntimeException {
  MigrationStorageException(Throwable cause) {
    super("PostgreSQL migration target operation failed.", cause);
  }

  MigrationStorageException(String message) {
    super(message);
  }

  MigrationStorageException(String message, Throwable cause) {
    super(message, cause);
  }
}

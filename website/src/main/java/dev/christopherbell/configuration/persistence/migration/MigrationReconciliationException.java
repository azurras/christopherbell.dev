package dev.christopherbell.configuration.persistence.migration;

public final class MigrationReconciliationException extends RuntimeException {
  public MigrationReconciliationException() {
    super("PostgreSQL migration reconciliation rejected publication.");
  }
}

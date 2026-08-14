package dev.christopherbell.configuration.persistence.migration;

import java.util.List;

/** Durable target effects; each method is one bounded target transaction. */
public interface MigrationTargetStore {
  MigrationCheckpoint checkpoint(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind);

  MigrationCheckpoint commitBatch(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint expected,
      List<TransformedMigrationDocument> documents,
      String nextCursor);

  MigrationCheckpoint completeStaging(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint expected);

  MigrationReconciliation reconcile(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind);

  void publish(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationReconciliation reconciliation);

  List<MigrationKindStatus> statuses(ValidatedMigrationContext context);
}

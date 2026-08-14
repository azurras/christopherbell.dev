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

  void requireStagedDocuments(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      List<TransformedMigrationDocument> documents);

  MigrationReconciliation reconcile(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind);

  void finalizeRun(
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      List<MigrationReconciliation> reconciliations);

  List<MigrationKindStatus> statuses(ValidatedMigrationContext context);
}

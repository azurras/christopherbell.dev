package dev.christopherbell.configuration.persistence.migration;

import java.util.ArrayList;

/** Stages one kind through deterministic, restartable, bounded source pages. */
public final class KindMigrationEngine {
  private final MigrationSourceReader source;
  private final MigrationTargetStore target;
  private final MigrationTransformerResolver transformers;

  public KindMigrationEngine(
      MigrationSourceReader source,
      MigrationTargetStore target,
      MigrationTransformerResolver transformers) {
    this.source = source;
    this.target = target;
    this.transformers = transformers;
  }

  public void stageAndCheckpoint(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    var checkpoint = target.checkpoint(context, kind);
    var transformer = transformers.require(kind.sourceKind());
    while (!checkpoint.complete()) {
      var batch = source.readAfter(
          context, kind, checkpoint.cursor(), context.request().batchSize());
      validateBatch(kind, checkpoint, batch, context.request().batchSize());
      if (batch.isEmpty()) {
        target.completeStaging(context, kind, checkpoint);
        return;
      }
      var transformed = new ArrayList<TransformedMigrationDocument>(batch.documents().size());
      for (var document : batch.documents()) {
        transformed.add(transformer.transform(document));
      }
      checkpoint = target.commitBatch(
          context, kind, checkpoint, transformed, batch.lastCursor());
    }
  }

  public void requireOnlyCatalogKinds(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog catalog) {
    if (source instanceof MigrationSourceCatalogGuard guard) {
      guard.requireOnlyCatalogKinds(context, catalog);
    }
  }

  /** Independently rereads the bounded source snapshot and rejects drift from the durable ledger. */
  public void requireSourceSnapshot(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint expected) {
    if (!expected.complete()) {
      throw new MigrationReconciliationException();
    }
    var actual = MigrationCheckpoint.initial();
    var transformer = transformers.require(kind.sourceKind());
    while (true) {
      var batch = source.readAfter(
          context, kind, actual.cursor(), context.request().batchSize());
      validateBatch(kind, actual, batch, context.request().batchSize());
      if (batch.isEmpty()) {
        break;
      }
      var transformed = new ArrayList<TransformedMigrationDocument>(batch.documents().size());
      for (var document : batch.documents()) {
        transformed.add(transformer.transform(document));
      }
      actual = actual.advance(batch.lastCursor(), transformed);
    }
    if (actual.sourceCount() != expected.sourceCount()
        || !actual.sourceDigest().equals(expected.sourceDigest())) {
      throw new MigrationReconciliationException();
    }
  }

  private static void validateBatch(
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint checkpoint,
      SourceBatch batch,
      int limit) {
    if (batch.documents().size() > limit) {
      throw invalidBatch();
    }
    String previous = null;
    for (var document : batch.documents()) {
      if (!kind.sourceKind().equals(document.sourceKind())
          || document.schemaVersion() != kind.sourceSchemaVersion()
          || document.sourceId() == null
          || document.sourceId().isBlank()
          || previous != null && document.sourceId().compareTo(previous) <= 0) {
        throw invalidBatch();
      }
      previous = document.sourceId();
    }
    if (!batch.isEmpty()
        && (batch.lastCursor().isBlank()
            || batch.lastCursor().equals(checkpoint.cursor()))) {
      throw invalidBatch();
    }
  }

  private static IllegalStateException invalidBatch() {
    return new IllegalStateException("PostgreSQL migration source batch is invalid.");
  }
}

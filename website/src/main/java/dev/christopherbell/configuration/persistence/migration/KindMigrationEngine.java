package dev.christopherbell.configuration.persistence.migration;

import java.util.ArrayList;
import java.util.List;

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
    String previousSourceId = null;
    while (!checkpoint.complete()) {
      var batch = source.readAfter(
          context, kind, checkpoint.cursor(), context.request().batchSize());
      previousSourceId = validateBatch(
          kind, checkpoint, batch, context.request().batchSize(), previousSourceId);
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
  public MigrationSourceSnapshot requireSourceSnapshot(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint expected) {
    if (!expected.complete()) {
      throw new MigrationReconciliationException();
    }
    var snapshot = readSourceSnapshot(
        context, kind, documents -> target.requireStagedDocuments(context, kind, documents));
    if (snapshot.sourceCount() != expected.sourceCount()
        || !snapshot.sourceDigest().equals(expected.sourceDigest())) {
      throw new MigrationReconciliationException();
    }
    return snapshot;
  }

  MigrationSourceSnapshot readSourceSnapshot(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    return readSourceSnapshot(context, kind, ignored -> {});
  }

  MigrationSourceSnapshot readSourceSnapshot(
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      java.util.function.Consumer<List<TransformedMigrationDocument>> stagedValidator) {
    return readSourceSnapshot(
        source, context, kind, transformers.require(kind.sourceKind()), stagedValidator);
  }

  static MigrationSourceSnapshot readSourceSnapshot(
      MigrationSourceReader source,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationTransformer transformer) {
    return readSourceSnapshot(source, context, kind, transformer, ignored -> {});
  }

  private static MigrationSourceSnapshot readSourceSnapshot(
      MigrationSourceReader source,
      ValidatedMigrationContext context,
      PostgresqlMigrationCatalog.Kind kind,
      MigrationTransformer transformer,
      java.util.function.Consumer<List<TransformedMigrationDocument>> stagedValidator) {
    var actual = MigrationCheckpoint.initial();
    var relationalDigest = MigrationCheckpoint.initial().sourceDigest();
    String previousSourceId = null;
    while (true) {
      var batch = source.readAfter(
          context, kind, actual.cursor(), context.request().batchSize());
      previousSourceId = validateBatch(
          kind, actual, batch, context.request().batchSize(), previousSourceId);
      if (batch.isEmpty()) {
        break;
      }
      var transformed = new ArrayList<TransformedMigrationDocument>(batch.documents().size());
      for (var document : batch.documents()) {
        transformed.add(transformer.transform(document));
      }
      stagedValidator.accept(List.copyOf(transformed));
      for (var document : transformed) {
        var targetHash = MigrationCanonicalizationRegistry.targetDocumentHash(
            kind, document.rows());
        relationalDigest = CanonicalMigrationHasher.sha256(java.util.List.of(
            relationalDigest, document.sourceId(), document.sourceHash(), targetHash));
      }
      actual = actual.advance(batch.lastCursor(), transformed);
    }
    return new MigrationSourceSnapshot(
        kind.sourceKind(), actual.sourceCount(), actual.sourceDigest(), relationalDigest);
  }

  private static String validateBatch(
      PostgresqlMigrationCatalog.Kind kind,
      MigrationCheckpoint checkpoint,
      SourceBatch batch,
      int limit,
      String previous) {
    if (batch.documents().size() > limit) {
      throw invalidBatch();
    }
    for (var document : batch.documents()) {
      if (!kind.sourceKind().equals(document.sourceKind())
          || document.schemaVersion() != kind.sourceSchemaVersion()
          || document.sourceId() == null
          || document.sourceId().isBlank()
          || previous != null
              && MongoSimpleStringOrder.compare(document.sourceId(), previous) <= 0) {
        throw invalidBatch();
      }
      previous = document.sourceId();
    }
    if (!batch.isEmpty()
        && (batch.lastCursor().isBlank()
            || batch.lastCursor().equals(checkpoint.cursor()))) {
      throw invalidBatch();
    }
    return previous;
  }

  private static IllegalStateException invalidBatch() {
    return new IllegalStateException("PostgreSQL migration source batch is invalid.");
  }
}

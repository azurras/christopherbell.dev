package dev.christopherbell.configuration.persistence.migration;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Non-web orchestration for shadow, finalize, reconcile, and status operations. */
public final class PostgresqlMigrationRunner {
  private final MigrationPreflight preflight;
  private final PostgresqlMigrationCatalog catalog;
  private final KindMigrationEngine engine;
  private final MigrationReconciler reconciler;
  private final MigrationTargetStore target;

  public PostgresqlMigrationRunner(
      MigrationPreflight preflight,
      PostgresqlMigrationCatalog catalog,
      KindMigrationEngine engine,
      MigrationReconciler reconciler,
      MigrationTargetStore target) {
    this.preflight = preflight;
    this.catalog = catalog;
    this.engine = engine;
    this.reconciler = reconciler;
    this.target = target;
  }

  public MigrationRunResult run(MigrationRequest request) {
    var context = preflight.validate(request);
    var kinds = catalog.kinds().stream()
        .sorted(Comparator.comparingInt(PostgresqlMigrationCatalog.Kind::loadOrder))
        .toList();
    switch (request.command()) {
      case SHADOW -> migrate(context, kinds, false);
      case FINALIZE -> migrate(context, kinds, true);
      case RECONCILE -> reconcileExisting(context, kinds);
      case STATUS -> {
        // Preflight still runs; status is otherwise read-only.
      }
    }
    var statuses = target.statuses(context);
    return new MigrationRunResult(
        request.command(), statuses, CanonicalMigrationHasher.sha256(statusValues(statuses)));
  }

  private void migrate(
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      boolean publish) {
    engine.requireOnlyCatalogKinds(context, catalog);
    var snapshots = new java.util.ArrayList<MigrationSourceSnapshot>(kinds.size());
    var reconciliations = new java.util.ArrayList<MigrationReconciliation>(kinds.size());
    for (var kind : kinds) {
      engine.stageAndCheckpoint(context, kind);
      var checkpoint = target.checkpoint(context, kind);
      snapshots.add(engine.requireSourceSnapshot(context, kind, checkpoint));
      reconciliations.add(reconciler.requireEquivalent(context, kind));
    }
    if (publish) {
      var evidence = context.request().frozenSourceEvidence();
      if (evidence == null
          || !evidence.sourceDigest().equals(MigrationSourceSnapshot.runDigest(snapshots))) {
        throw new MigrationReconciliationException();
      }
      target.finalizeRun(context, kinds, reconciliations);
    }
  }

  private void reconcileExisting(
      ValidatedMigrationContext context, List<PostgresqlMigrationCatalog.Kind> kinds) {
    var existing = target.statuses(context).stream()
        .collect(java.util.stream.Collectors.toMap(MigrationKindStatus::sourceKind, status -> status));
    for (var kind : kinds) {
      var status = existing.get(kind.sourceKind());
      if (status == null || !status.checkpoint().complete()) {
        throw new MigrationReconciliationException();
      }
      engine.requireSourceSnapshot(context, kind, status.checkpoint());
      reconciler.requireEquivalent(context, kind);
    }
  }

  private static List<Object> statusValues(List<MigrationKindStatus> statuses) {
    return statuses.stream().map(status -> {
      var values = new LinkedHashMap<String, Object>();
      values.put("kind", status.sourceKind());
      values.put("cursor", status.checkpoint().cursor());
      values.put("complete", status.checkpoint().complete());
      values.put("sourceCount", status.checkpoint().sourceCount());
      values.put("sourceDigest", status.checkpoint().sourceDigest());
      values.put("publishedCount", status.publishedCount());
      values.put("published", status.published());
      return (Object) values;
    }).toList();
  }
}

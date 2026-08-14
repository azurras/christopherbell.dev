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
  private final FinalizeAuthorityProvider finalizeAuthority;
  private final FinalizationFreezeGuardProvider freezeGuard;

  public PostgresqlMigrationRunner(
      MigrationPreflight preflight,
      PostgresqlMigrationCatalog catalog,
      KindMigrationEngine engine,
      MigrationReconciler reconciler,
      MigrationTargetStore target,
      FinalizeAuthorityProvider finalizeAuthority,
      FinalizationFreezeGuardProvider freezeGuard) {
    this.preflight = preflight;
    this.catalog = catalog;
    this.engine = engine;
    this.reconciler = reconciler;
    this.target = target;
    this.finalizeAuthority = java.util.Objects.requireNonNull(
        finalizeAuthority, "finalizeAuthority");
    this.freezeGuard = java.util.Objects.requireNonNull(freezeGuard, "freezeGuard");
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
      var verifiedEvidence = finalizeAuthority.reload(evidence);
      if (verifiedEvidence == null || !verifiedEvidence.equals(evidence)) {
        throw new MigrationReconciliationException();
      }
      try (var guard = freezeGuard.acquire(context, verifiedEvidence)) {
        guard.requireLocked();
        target.finalizeRun(context, kinds, reconciliations,
            () -> revalidateFrozenBoundary(context, kinds, reconciliations, guard));
      }
    } else {
      target.rehearseShadow(context, kinds, reconciliations);
    }
  }

  private FrozenSourceEvidence revalidateFrozenBoundary(
      ValidatedMigrationContext context,
      List<PostgresqlMigrationCatalog.Kind> kinds,
      List<MigrationReconciliation> reconciliations,
      FinalizationFreezeGuard guard) {
    var expected = context.request().frozenSourceEvidence();
    guard.requireLocked();
    var before = finalizeAuthority.reload(expected);
    if (before == null || !before.equals(expected)) {
      throw new MigrationReconciliationException();
    }
    var snapshots = new java.util.ArrayList<MigrationSourceSnapshot>(kinds.size());
    for (var index = 0; index < kinds.size(); index++) {
      var snapshot = engine.readSourceSnapshot(context, kinds.get(index));
      var reconciliation = reconciliations.get(index);
      if (snapshot.sourceCount() != reconciliation.sourceCount()
          || !snapshot.sourceDigest().equals(reconciliation.sourceDigest())) {
        throw new MigrationReconciliationException();
      }
      snapshots.add(snapshot);
    }
    if (!before.sourceDigest().equals(MigrationSourceSnapshot.runDigest(snapshots))) {
      throw new MigrationReconciliationException();
    }
    var after = finalizeAuthority.reload(expected);
    if (!before.equals(after)) {
      throw new MigrationReconciliationException();
    }
    guard.requireLocked();
    return after;
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

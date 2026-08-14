package dev.christopherbell.configuration.persistence.migration;

/** Fail-closed reconciliation and publication gate. */
public final class MigrationReconciler {
  private final MigrationTargetStore target;

  public MigrationReconciler(MigrationTargetStore target) {
    this.target = target;
  }

  public MigrationReconciliation requireEquivalent(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    var result = target.reconcile(context, kind);
    if (!result.equivalent()) {
      throw new MigrationReconciliationException();
    }
    return result;
  }

  public void reconcileAndPublish(
      ValidatedMigrationContext context, PostgresqlMigrationCatalog.Kind kind) {
    target.publish(context, kind, requireEquivalent(context, kind));
  }
}

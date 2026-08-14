package dev.christopherbell.configuration.persistence.migration;

@FunctionalInterface
interface FinalizationFreezeGuardProvider {
  FinalizationFreezeGuard acquire(
      ValidatedMigrationContext context, FrozenSourceEvidence verifiedEvidence);
}

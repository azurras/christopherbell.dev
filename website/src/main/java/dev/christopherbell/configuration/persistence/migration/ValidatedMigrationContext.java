package dev.christopherbell.configuration.persistence.migration;

/** Trusted migration request plus identities observed from both database engines. */
public record ValidatedMigrationContext(
    MigrationRequest request,
    MigrationDatabaseIdentity sourceIdentity,
    MigrationDatabaseIdentity targetIdentity,
    boolean sourceFrozen) {}

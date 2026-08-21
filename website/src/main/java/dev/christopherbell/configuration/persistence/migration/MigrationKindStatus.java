package dev.christopherbell.configuration.persistence.migration;

/** Redaction-safe operational status for one catalog kind. */
public record MigrationKindStatus(
    String sourceKind,
    MigrationCheckpoint checkpoint,
    long publishedCount,
    boolean published) {}

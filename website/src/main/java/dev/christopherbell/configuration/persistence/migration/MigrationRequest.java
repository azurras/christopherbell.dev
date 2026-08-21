package dev.christopherbell.configuration.persistence.migration;

import java.util.UUID;

/** Untrusted command input validated before either database is opened. */
public record MigrationRequest(
    PostgresqlMigrationCommand command,
    String sourceUri,
    String sourceDatabase,
    String targetJdbcUrl,
    String targetDatabase,
    String expectedTargetRole,
    String schemaPrefix,
    String catalogDigest,
    String release,
    int bridgeRelease,
    UUID lockToken,
    FrozenSourceEvidence frozenSourceEvidence,
    int batchSize) {}

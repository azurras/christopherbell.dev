package dev.christopherbell.configuration.persistence.migration;

/** Redaction-safe identity observed directly from one migration database connection. */
public record MigrationDatabaseIdentity(String host, int port, String database, String role) {}

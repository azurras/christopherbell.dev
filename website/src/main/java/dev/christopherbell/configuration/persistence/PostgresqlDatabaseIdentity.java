package dev.christopherbell.configuration.persistence;

/** Safe database identity values read by the PostgreSQL test guard. */
public record PostgresqlDatabaseIdentity(String database, String schema) {}

package dev.christopherbell.configuration.persistence;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/** Prefixes only canonical schema names; table and column names remain Flyway-owned. */
@PostgresPersistenceSupport
public final class PostgresqlPhysicalNamingStrategy extends PhysicalNamingStrategyStandardImpl {
  private final PostgresqlSchemaNames schemaNames;

  public PostgresqlPhysicalNamingStrategy(PostgresqlSchemaNames schemaNames) {
    this.schemaNames = schemaNames;
  }

  @Override
  public Identifier toPhysicalSchemaName(Identifier logicalName, JdbcEnvironment environment) {
    if (logicalName == null) {
      return null;
    }
    return Identifier.toIdentifier(
        schemaNames.schema(logicalName.getText()), logicalName.isQuoted());
  }
}

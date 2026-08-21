package dev.christopherbell.federation.api;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.federation.outbound.FederationMigrationAdapterVerifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Published federation-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class FederationMigrationVerifier {
  private FederationMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) throws SQLException {
    return FederationMigrationAdapterVerifier.verify(
        connection, schema, sourceKind, queryName, rows);
  }
}

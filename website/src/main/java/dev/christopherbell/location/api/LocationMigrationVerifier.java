package dev.christopherbell.location.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.location.zip.PostgresZipCoordinateImportStateRepository;
import dev.christopherbell.location.zip.PostgresZipCoordinateRepository;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** Published location-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class LocationMigrationVerifier {
  private LocationMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind,
      List<Map<String, Object>> rows) {
    return switch (sourceKind) {
      case "zip_coordinate" -> verifyOptionalLookup(
          rows, "zip_code", new PostgresZipCoordinateRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema))::findById);
      case "zip_import_state" -> verifyOptionalLookup(
          rows, "import_state_id", new PostgresZipCoordinateImportStateRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema))::findById);
      default -> false;
    };
  }
}

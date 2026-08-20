package dev.christopherbell.location.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
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
    var context = database(connection, schema);
    return switch (sourceKind) {
      case "zip_coordinate" -> verifyOptionalLookup(
          rows, "zip_code", new PostgresZipCoordinateRepository(context)::findById);
      case "zip_import_state" -> verifyOptionalLookup(
          rows, "import_state_id", new PostgresZipCoordinateImportStateRepository(context)::findById);
      default -> false;
    };
  }
}

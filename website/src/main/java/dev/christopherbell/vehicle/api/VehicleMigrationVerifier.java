package dev.christopherbell.vehicle.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.vehicle.core.PostgresVehicleRepository;
import dev.christopherbell.vehicle.nhtsa.decode.PostgresVehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.enrichment.PostgresNhtsaVinImportStateRepository;
import dev.christopherbell.vehicle.randomvin.importing.PostgresRandomVinImportStateRepository;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/** Published vehicle-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class VehicleMigrationVerifier {
  private VehicleMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) {
    return switch (sourceKind + "/" + queryName) {
      case "vehicle/find-by-id" -> verifyOptionalLookup(
          rows, "vehicle_id", vehicles(connection, schema)::findById);
      case "vehicle/find-by-vin" -> verifyVins(connection, schema, rows);
      case "vin_decode_cache/find-by-vin" -> verifyOptionalLookup(
          rows, "vin", new PostgresVehicleVinDecodeCacheRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema),
              org.springframework.transaction.support.TransactionOperations.withoutTransaction())
              ::findById);
      case "nhtsa_import_state/find-by-id" -> verifyOptionalLookup(
          rows, "import_state_id",
          new PostgresNhtsaVinImportStateRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema))::findById);
      case "random_vin_import_state/find-by-id" -> verifyOptionalLookup(
          rows, "import_state_id",
          new PostgresRandomVinImportStateRepository(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema))::findById);
      default -> false;
    };
  }

  private static boolean verifyVins(
      Connection connection, String schema, List<Map<String, Object>> rows) {
    var repository = vehicles(connection, schema);
    return rows.stream().map(row -> text(row.get("vin")))
        .filter(java.util.Objects::nonNull).allMatch(repository::existsByVin)
        && !repository.existsByVin("MIGRATIONVERIFIER0");
  }

  private static PostgresVehicleRepository vehicles(Connection connection, String schema) {
    return new PostgresVehicleRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(schema),
        org.springframework.transaction.support.TransactionOperations.withoutTransaction());
  }
}

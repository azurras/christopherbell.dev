package dev.christopherbell.canesboxtracker.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.canesboxtracker.PostgresCanesBoxPriceSnapshotRepository;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Published Cane's Box Tracker adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class CanesBoxTrackerMigrationVerifier {
  private CanesBoxTrackerMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String queryName,
      List<Map<String, Object>> rows) {
    var repository = new PostgresCanesBoxPriceSnapshotRepository(database(connection, schema));
    return switch (queryName) {
      case "find-by-id" -> verifyOptionalLookup(
          rows, "price_snapshot_id", repository::findById);
      case "weekly-snapshot-page" -> {
        var expected = rows.stream().sorted(Comparator.comparing(
            (Map<String, Object> row) -> (java.time.LocalDate) row.get("week_start_date"))
            .reversed()).limit(60)
            .map(row -> row.get("price_snapshot_id").toString()).toList();
        var actual = repository.findTop60ByOrderByWeekStartDateDesc().stream()
            .map(value -> value.getId()).toList();
        yield actual.equals(expected);
      }
      default -> false;
    };
  }
}

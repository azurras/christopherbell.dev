package dev.christopherbell.configuration.persistence;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.configuration.security.browser.PostgresBrowserSessionAuthenticationStore;
import dev.christopherbell.libs.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Published platform adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class PlatformMigrationVerifier {
  private PlatformMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind,
      List<Map<String, Object>> rows) throws SQLException {
    var context = database(connection, schema);
    return switch (sourceKind) {
      case "browser_session" -> verifyOptionalLookup(
          rows, "browser_session_id",
          new PostgresBrowserSessionAuthenticationStore(context)::findById);
      case "application_lease" -> ApplicationLeaseMigrationVerifier.verify(connection, schema);
      case "scheduled_collector_run" -> verifyScheduled(connection, context, rows);
      default -> false;
    };
  }

  private static boolean verifyScheduled(
      Connection connection, org.jooq.DSLContext context, List<Map<String, Object>> rows)
      throws SQLException {
    return rollback(connection, () -> {
      var store = new PostgresScheduledCollectorRunStore(context);
      for (var row : rows) {
        var run = ScheduledCollectorRun.builder()
            .id(text(row.get("collector_run_id")))
            .collectorName(text(row.get("collector_name")))
            .ownerToken(text(row.get("owner_token")))
            .status(ScheduledCollectorRunStatus.valueOf(text(row.get("status"))))
            .startedOn(instant(row.get("started_on")))
            .completedOn(instant(row.get("completed_on")))
            .errorCategory(text(row.get("error_category")))
            .build();
        var saved = store.save(run);
        if (!run.getId().equals(saved.getId()) || run.getStatus() != saved.getStatus()) {
          return false;
        }
      }
      return true;
    });
  }
}

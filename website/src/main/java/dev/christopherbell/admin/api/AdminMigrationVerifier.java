package dev.christopherbell.admin.api;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.admin.activity.AdminActivityQuery;
import dev.christopherbell.admin.activity.PostgresAdminActivityQueryRepository;
import dev.christopherbell.admin.activity.PostgresAdminActivityRepository;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionType;
import dev.christopherbell.admin.commandcenter.action.PendingActionStore.Reservation;
import dev.christopherbell.admin.commandcenter.action.PostgresPendingActionStore;
import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Published admin-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class AdminMigrationVerifier {
  private AdminMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) throws SQLException {
    var context = database(connection, schema);
    return switch (sourceKind + "/" + queryName) {
      case "admin_activity/find-by-id" -> verifyOptionalLookup(
          rows, "admin_activity_id", new PostgresAdminActivityRepository(context)::findById);
      case "admin_activity/query" -> verifyQuery(context, rows);
      case "pending_action/active" -> verifyActive(context, rows);
      case "pending_action/reserve" -> verifyReserve(connection, context);
      default -> false;
    };
  }

  private static boolean verifyQuery(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var expected = rows.stream().sorted(Comparator.comparing(
        (Map<String, Object> row) -> instant(row.get("created_on"))).reversed()
        .thenComparing(row -> text(row.get("admin_activity_id")), Comparator.reverseOrder()))
        .toList();
    var actual = new PostgresAdminActivityQueryRepository(context)
        .query(new AdminActivityQuery(null, null, null, null, null, 0, 2));
    return actual.totalElements() == expected.size()
        && actual.items().stream().map(value -> value.getId()).toList()
            .equals(expected.stream().limit(2)
                .map(row -> text(row.get("admin_activity_id"))).toList());
  }

  private static boolean verifyActive(
      org.jooq.DSLContext context, List<Map<String, Object>> rows) {
    var actual = new PostgresPendingActionStore(context).active(Instant.EPOCH);
    return rows.isEmpty() ? actual.isEmpty() : actual.isPresent()
        && text(rows.getFirst().get("action")).equals(actual.orElseThrow().action().name());
  }

  private static boolean verifyReserve(
      Connection connection, org.jooq.DSLContext context) throws SQLException {
    return rollback(connection, () -> {
      var store = new PostgresPendingActionStore(context);
      var accepted = Instant.parse("2099-01-01T00:00:00Z");
      var reservation = new Reservation(
          CommandCenterActionType.RESTART_COMPUTER, accepted, accepted.plusSeconds(60));
      store.reconcile(Instant.parse("2100-01-01T00:00:00Z"));
      return store.reserve(reservation, accepted)
          && !store.reserve(new Reservation(
              CommandCenterActionType.SHUTDOWN_COMPUTER,
              accepted.plusSeconds(1), accepted.plusSeconds(61)), accepted.plusSeconds(1));
    });
  }
}

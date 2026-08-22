package dev.christopherbell.admin.api;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Published admin-module adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class AdminMigrationVerifier {
  private AdminMigrationVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> rows) throws SQLException {
    var dataSource = new SingleConnectionDataSource(connection, true);
    var database = JdbcClient.create(dataSource);
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    var transactions = new TransactionTemplate(
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    return switch (sourceKind + "/" + queryName) {
      case "admin_activity/find-by-id" -> verifyOptionalLookup(
          rows, "admin_activity_id",
          new PostgresAdminActivityRepository(database, schemas, transactions)::findById);
      case "admin_activity/query" -> verifyQuery(database, schemas, rows);
      case "pending_action/active" -> verifyActive(connection, schema, rows);
      case "pending_action/reserve" -> verifyReserve(connection, schema);
      default -> false;
    };
  }

  private static boolean verifyQuery(
      JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      List<Map<String, Object>> rows) {
    var expected = rows.stream().sorted(Comparator.comparing(
        (Map<String, Object> row) -> instant(row.get("created_on"))).reversed()
        .thenComparing(row -> text(row.get("admin_activity_id")), Comparator.reverseOrder()))
        .toList();
    var actual = new PostgresAdminActivityQueryRepository(database, schemas)
        .query(new AdminActivityQuery(null, null, null, null, null, 0, 2));
    return actual.totalElements() == expected.size()
        && actual.items().stream().map(value -> value.getId()).toList()
            .equals(expected.stream().limit(2)
                .map(row -> text(row.get("admin_activity_id"))).toList());
  }

  private static boolean verifyActive(
      Connection connection, String schema, List<Map<String, Object>> rows)
      throws SQLException {
    if (rows.size() > 1) {
      return false;
    }
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(
        new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    var targetDeadline = jdbc.sql("select execute_at from %s where pending_action_id = :id"
            .formatted(schemas.qualifiedTable("platform", "pending_action")))
        .param("id", "machine-power").query(java.time.OffsetDateTime.class).optional()
        .map(value -> value.toInstant());
    var deadline = rows.isEmpty()
        ? targetDeadline.orElse(Instant.EPOCH)
        : instant(rows.getFirst().get("execute_at"));
    var observationTime = deadline.minusNanos(1);
    return rollback(connection, () -> {
      var actual = new PostgresPendingActionStore(jdbc, schemas).active(observationTime);
      if (rows.isEmpty()) {
        return actual.isEmpty();
      }
      var expected = rows.getFirst();
      return actual.isPresent()
          && text(expected.get("action")).equals(actual.orElseThrow().action().name())
          && instant(expected.get("accepted_at")).equals(actual.orElseThrow().acceptedAt())
          && deadline.equals(actual.orElseThrow().executeAt());
    });
  }

  private static boolean verifyReserve(
      Connection connection, String schema) throws SQLException {
    return rollback(connection, () -> {
      var store = new PostgresPendingActionStore(
          org.springframework.jdbc.core.simple.JdbcClient.create(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
          dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
              .fromPhysicalSchema(schema));
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

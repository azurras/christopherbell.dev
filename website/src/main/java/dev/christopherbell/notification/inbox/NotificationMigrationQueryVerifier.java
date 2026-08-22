package dev.christopherbell.notification.inbox;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.notification.PostgresNotificationRepository;
import dev.christopherbell.notification.delivery.NotificationDeliveryProperties;
import dev.christopherbell.notification.delivery.NotificationEventIdentity;
import dev.christopherbell.notification.delivery.PostgresNotificationFanoutGuard;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.notification.preference.PostgresNotificationPreferenceRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Executes the production notification adapter for cutover parity checks. */
@PostgresPersistenceSupport
public final class NotificationMigrationQueryVerifier {
  private NotificationMigrationQueryVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> sourceRows) throws SQLException {
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(
        new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
    var schemas = dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
        .fromPhysicalSchema(schema);
    return switch (sourceKind + "/" + queryName) {
      case "notification/find-by-id" ->
          dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport
              .verifyOptionalLookup(sourceRows, "notification_id",
                  new PostgresNotificationRepository(jdbc, schemas)::findById);
      case "notification/account-page" -> verifyAccountPage(connection, schema, sourceRows);
      case "notification/unread-by-account" -> verifyUnread(jdbc, schemas, sourceRows);
      case "notification_preference/find-by-account" ->
          dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport
              .verifyOptionalLookup(sourceRows, "account_id",
                  new PostgresNotificationPreferenceRepository(
                      org.springframework.jdbc.core.simple.JdbcClient.create(
                          new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                              connection, true)),
                      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                          .fromPhysicalSchema(schema))::findByAccountId);
      case "notification_delivery_guard/try-acquire",
          "notification_rate_limit/try-acquire" ->
              verifyGuard(connection, schema, sourceRows);
      default -> false;
    };
  }

  public static boolean verifyAccountPage(
      Connection connection, String communicationSchema, List<Map<String, Object>> sourceRows) {
    var repository = new PostgresNotificationQueryRepository(
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)),
        dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
            .fromPhysicalSchema(communicationSchema),
        new StableCursorCodec());
    for (var accountId : sourceRows.stream().map(row -> text(row.get("account_id")))
        .filter(Objects::nonNull).distinct().toList()) {
      var expected = sourceRows.stream()
          .filter(row -> accountId.equals(text(row.get("account_id"))))
          .sorted(Comparator.comparing(NotificationMigrationQueryVerifier::createdOn).reversed()
              .thenComparing(row -> text(row.get("notification_id")), Comparator.reverseOrder()))
          .toList();
      var first = repository.page(accountId, Optional.empty(), 1);
      if (!matches(first, expected)) {
        return false;
      }
      if (!expected.isEmpty()) {
        var boundary = expected.getFirst();
        var cursor = new StableCursor(
            createdOn(boundary), text(boundary.get("notification_id")));
        if (!matches(repository.page(accountId, Optional.of(cursor), 1), expected.stream().skip(1).toList())) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean verifyUnread(
      org.springframework.jdbc.core.simple.JdbcClient database,
      dev.christopherbell.configuration.persistence.PostgresqlSchemaNames schemas,
      List<Map<String, Object>> rows) {
    var repository = new PostgresNotificationRepository(database, schemas);
    return rows.stream().map(row -> text(row.get("account_id")))
        .filter(Objects::nonNull).distinct().allMatch(accountId ->
            repository.countByAccountIdAndReadFalse(accountId)
                == rows.stream().filter(row -> accountId.equals(text(row.get("account_id"))))
                    .filter(row -> !Boolean.TRUE.equals(row.get("is_read"))).count());
  }

  private static boolean verifyGuard(
      Connection connection,
      String schema,
      List<Map<String, Object>> rows) throws SQLException {
    if (rows.isEmpty()) {
      return true;
    }
    var row = rows.getFirst();
    return dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport
        .rollback(connection, () -> {
          var guard = new PostgresNotificationFanoutGuard(
              org.springframework.jdbc.core.simple.JdbcClient.create(
                  new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                      connection, true)),
              dev.christopherbell.configuration.persistence.PostgresqlSchemaNames
                  .fromPhysicalSchema(schema),
              org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
              new NotificationDeliveryProperties(
                  Duration.ofMinutes(5), Duration.ofMinutes(5), 1));
          var identity = new NotificationEventIdentity(
              text(row.get("account_id")), text(row.get("actor_account_id")),
              NotificationType.LIKE, "migration-verifier-target");
          var now = Instant.parse("2026-08-20T00:00:00Z");
          var first = guard.tryAcquire(identity, now);
          if (first.isEmpty() || guard.tryAcquire(identity, now).isPresent()) {
            return false;
          }
          guard.release(first.orElseThrow());
          return guard.tryAcquire(identity, now).isPresent();
        });
  }

  private static boolean matches(NotificationPage actual, List<Map<String, Object>> expected) {
    var expectedIds = expected.stream().limit(1)
        .map(row -> text(row.get("notification_id"))).toList();
    return actual.items().stream().map(NotificationDetail::id).toList().equals(expectedIds)
        && (actual.nextCursor() != null) == (expected.size() > 1);
  }

  private static Instant createdOn(Map<String, Object> row) {
    return (Instant) row.get("created_on");
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}

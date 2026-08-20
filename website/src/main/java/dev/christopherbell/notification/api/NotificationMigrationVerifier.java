package dev.christopherbell.notification.api;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.notification.inbox.NotificationMigrationQueryVerifier;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Published notification-module cutover parity operation. */
@PostgresPersistenceSupport
public final class NotificationMigrationVerifier {
  private NotificationMigrationVerifier() {}

  public static boolean verifyAccountPage(
      Connection connection, String schema, List<Map<String, Object>> sourceRows) {
    return NotificationMigrationQueryVerifier.verifyAccountPage(connection, schema, sourceRows);
  }

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> sourceRows) throws SQLException {
    return NotificationMigrationQueryVerifier.verify(
        connection, schema, sourceKind, queryName, sourceRows);
  }
}

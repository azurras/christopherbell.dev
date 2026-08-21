package dev.christopherbell.sharedfolder.api;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseMigrationVerifier;
import dev.christopherbell.sharedfolder.audit.PostgresSharedFolderAuditRepository;
import dev.christopherbell.sharedfolder.media.PostgresMediaJobRepository;
import dev.christopherbell.sharedfolder.radio.PostgresSharedFolderRadioRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleMigrationQueryVerifier;
import dev.christopherbell.sharedfolder.recycle.PostgresSharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.service.PostgresSharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.upload.PostgresSharedFolderUploadSessionRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Published shared-folder cutover parity operations. */
@PostgresPersistenceSupport
public final class SharedFolderMigrationVerifier {
  private SharedFolderMigrationVerifier() {}

  public static boolean verifyRecycleStatePage(
      Connection connection, String schema, List<Map<String, Object>> sourceRows) {
    return SharedFolderRecycleMigrationQueryVerifier.verifyStateDeletedPage(
        connection, schema, sourceRows);
  }

  public static boolean verifyMaintenanceLease(Connection connection, String schema)
      throws SQLException {
    return SharedFolderMaintenanceLeaseMigrationVerifier.verify(connection, schema);
  }

  public static boolean verify(
      Connection connection, String schema, String sourceKind, String queryName,
      List<Map<String, Object>> sourceRows) throws SQLException {
    var database = dev.christopherbell.configuration.persistence
        .PostgresqlMigrationVerificationSupport.database(connection, schema);
    return switch (sourceKind + "/" + queryName) {
      case "audit_event/search" -> verifyAudit(database, sourceRows);
      case "maintenance_lease/claim-expired-lease" -> verifyMaintenanceLease(connection, schema);
      case "media_job/find-by-id" -> verifyLookup(sourceRows, "media_job_id",
          new PostgresMediaJobRepository(database)::findById);
      case "mutation_recovery/find-by-id" -> verifyLookup(sourceRows, "mutation_recovery_id",
          new PostgresSharedFolderMutationRecoveryRepository(database)::findById);
      case "radio_state/find-by-id" -> verifyLookup(sourceRows, "radio_state_id",
          new PostgresSharedFolderRadioRepository(database)::findById);
      case "recycle_item/find-by-id" -> verifyLookup(sourceRows, "recycle_item_id",
          new PostgresSharedFolderRecycleRepository(database)::findById);
      case "recycle_item/state-deleted-page" ->
          verifyRecycleStatePage(connection, schema, sourceRows);
      case "upload_session/find-by-id" -> verifyLookup(sourceRows, "upload_session_id",
          new PostgresSharedFolderUploadSessionRepository(database)::findById);
      default -> false;
    };
  }

  private static boolean verifyLookup(
      List<Map<String, Object>> rows, String key,
      java.util.function.Function<String, java.util.Optional<?>> lookup) {
    return dev.christopherbell.configuration.persistence
        .PostgresqlMigrationVerificationSupport.verifyOptionalLookup(rows, key, lookup);
  }

  private static boolean verifyAudit(
      org.jooq.DSLContext database, List<Map<String, Object>> rows) {
    var repository = new PostgresSharedFolderAuditRepository(database);
    var expected = rows.stream().sorted(java.util.Comparator.comparing(
        (Map<String, Object> row) -> dev.christopherbell.configuration.persistence
            .PostgresqlMigrationVerificationSupport.instant(row.get("occurred_at"))).reversed()
        .thenComparing(row -> dev.christopherbell.configuration.persistence
            .PostgresqlMigrationVerificationSupport.text(row.get("audit_event_id")),
            java.util.Comparator.reverseOrder()))
        .map(row -> dev.christopherbell.configuration.persistence
            .PostgresqlMigrationVerificationSupport.text(row.get("audit_event_id")))
        .limit(100).toList();
    var actual = repository.search(null, null, null, null, null, null, 100).stream()
        .map(value -> value.id()).toList();
    return actual.equals(expected);
  }
}

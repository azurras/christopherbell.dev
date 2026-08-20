package dev.christopherbell.configuration.persistence;

import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.database;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.instant;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.rollback;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.text;
import static dev.christopherbell.configuration.persistence.PostgresqlMigrationVerificationSupport.verifyOptionalLookup;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.security.browser.PostgresBrowserSessionAuthenticationStore;
import dev.christopherbell.libs.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Published platform adapter operations used by cutover parity. */
@PostgresPersistenceSupport
public final class PlatformMigrationVerifier {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> LEGACY_SOURCE_NAMES = DomainCollectionManifest.ALL_KINDS.stream()
      .flatMap(kind -> kind.legacySource().stream())
      .collect(java.util.stream.Collectors.toUnmodifiableSet());

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

  /** Executes the platform-owned invariants for ledgers that intentionally have no query port. */
  public static boolean verifyLedger(
      String sourceKind, Map<String, List<Map<String, Object>>> tables) {
    return switch (sourceKind) {
      case "migration_record" -> verifyMigrationRecords(tables);
      case "domain_collection_cutover" -> verifyDomainCollectionCutovers(tables);
      default -> false;
    };
  }

  private static boolean verifyMigrationRecords(
      Map<String, List<Map<String, Object>>> tables) {
    if (!tables.keySet().equals(Set.of("application_migration_record"))) {
      return false;
    }
    for (var row : tables.get("application_migration_record")) {
      var status = text(row.get("status"));
      var startedAt = instant(row.get("started_at"));
      var completedAt = instant(row.get("completed_at"));
      var failureCategory = text(row.get("failure_category"));
      if (startedAt == null || completedAt != null && completedAt.isBefore(startedAt)) {
        return false;
      }
      var validState = switch (status == null ? "" : status) {
        case "RUNNING" -> completedAt == null && failureCategory == null;
        case "APPLIED" -> completedAt != null && failureCategory == null;
        case "FAILED" -> completedAt != null
            && failureCategory != null && !failureCategory.isBlank();
        default -> false;
      };
      if (!validState) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyDomainCollectionCutovers(
      Map<String, List<Map<String, Object>>> tables) {
    if (!tables.keySet().equals(Set.of(
        "domain_collection_cutover", "domain_collection_cutover_source",
        "domain_collection_cutover_metric"))) {
      return false;
    }
    var cutoverIds = tables.get("domain_collection_cutover").stream()
        .map(row -> text(row.get("cutover_id"))).toList();
    if (cutoverIds.stream().anyMatch(java.util.Objects::isNull)
        || cutoverIds.stream().distinct().count() != cutoverIds.size()) {
      return false;
    }
    var sourcesByCutover = groupByCutover(tables.get("domain_collection_cutover_source"));
    var metricsByCutover = groupByCutover(tables.get("domain_collection_cutover_metric"));
    var cutoverIdSet = Set.copyOf(cutoverIds);
    if (sourcesByCutover.containsKey(null) || metricsByCutover.containsKey(null)
        || !cutoverIdSet.containsAll(sourcesByCutover.keySet())
        || !cutoverIdSet.containsAll(metricsByCutover.keySet())) {
      return false;
    }
    for (var cutoverId : cutoverIds) {
      if (!verifySources(sourcesByCutover.getOrDefault(cutoverId, List.of()))
          || !verifyMetrics(metricsByCutover.getOrDefault(cutoverId, List.of()))) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, List<Map<String, Object>>> groupByCutover(
      List<Map<String, Object>> rows) {
    var result = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var row : rows) {
      result.computeIfAbsent(text(row.get("cutover_id")), ignored -> new ArrayList<>()).add(row);
    }
    return result;
  }

  private static boolean verifySources(List<Map<String, Object>> rows) {
    for (var index = 0; index < rows.size(); index++) {
      var row = rows.get(index);
      var sourceName = text(row.get("source_name"));
      if (!Integer.valueOf(index).equals(integer(row.get("ordinal")))
          || !LEGACY_SOURCE_NAMES.contains(sourceName)
          || index > 0
              && text(rows.get(index - 1).get("source_name")).compareTo(sourceName) >= 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean verifyMetrics(List<Map<String, Object>> rows) {
    if (rows.size() > DomainCollectionManifest.ALL_KINDS.size()) {
      return false;
    }
    for (var index = 0; index < rows.size(); index++) {
      var row = rows.get(index);
      if (!Integer.valueOf(index).equals(integer(row.get("ordinal")))
          || !DomainCollectionManifest.ALL_KINDS.get(index).kind()
              .equals(text(row.get("source_kind")))
          || longValue(row.get("source_count")) == null
          || longValue(row.get("source_count")) < 0
          || !SHA256.matcher(java.util.Objects.toString(row.get("checksum"), "")).matches()) {
        return false;
      }
    }
    return true;
  }

  private static Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private static Long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : null;
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

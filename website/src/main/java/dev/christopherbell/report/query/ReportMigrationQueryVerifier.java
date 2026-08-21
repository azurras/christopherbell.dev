package dev.christopherbell.report.query;

import dev.christopherbell.configuration.persistence.PostgresPersistenceSupport;
import dev.christopherbell.report.PostgresReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.sql.Connection;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/** Executes the production moderation-query service for cutover parity checks. */
@PostgresPersistenceSupport
public final class ReportMigrationQueryVerifier {
  private ReportMigrationQueryVerifier() {}

  public static boolean verify(
      Connection connection, String schema, String queryName,
      List<Map<String, Object>> sourceRows) {
    var database = DSL.using(connection, SQLDialect.POSTGRES, settings(schema));
    var repository = new PostgresReportRepository(database);
    return switch (queryName) {
      case "find-by-id" -> dev.christopherbell.configuration.persistence
          .PostgresqlMigrationVerificationSupport.verifyOptionalLookup(
              sourceRows, "post_report_id", repository::findById);
      case "moderation-page" -> verifyModerationPage(connection, schema, sourceRows);
      case "find-open-dedupe" -> sourceRows.stream()
          .filter(row -> "OPEN".equals(text(row.get("status"))))
          .filter(row -> text(row.get("open_dedupe_key")) != null)
          .allMatch(row -> repository.findByOpenDedupeKey(
              text(row.get("open_dedupe_key"))).isPresent())
          && repository.findByOpenDedupeKey(
              "migration-verifier-missing-dedupe").isEmpty();
      default -> false;
    };
  }

  public static boolean verifyModerationPage(
      Connection connection, String schema, List<Map<String, Object>> sourceRows) {
    var database = DSL.using(connection, SQLDialect.POSTGRES, settings(schema));
    var service = new PostgresReportQueryService(database, new PostgresReportRepository(database));
    var all = new ReportQuery(null, null, null, null, null, null, 0, 2);
    try {
      if (!matches(service.query(all), expected(sourceRows, all), 2)) {
        return false;
      }
      for (var row : sourceRows) {
        var created = instant(row.get("created_on"));
        var reporter = text(row.get("reporter_username"));
        var filtered = new ReportQuery(
            ReportStatus.valueOf(text(row.get("status"))),
            ReportType.valueOf(text(row.get("report_type"))),
            ReportTargetType.valueOf(text(row.get("target_type"))),
            reporter == null || reporter.isBlank()
                ? null : reporter.substring(0, 1).toLowerCase(Locale.ROOT),
            created.minusSeconds(1), created.plusSeconds(1), 0, 100);
        if (!matches(service.query(filtered), expected(sourceRows, filtered), 100)) {
          return false;
        }
      }
      return true;
    } catch (dev.christopherbell.libs.api.exception.InvalidRequestException invalidFixture) {
      return false;
    }
  }

  private static boolean matches(ReportPage actual, List<Map<String, Object>> expected, int size) {
    return actual.items().stream().map(PostReport::getId).toList()
            .equals(expected.stream().limit(size).map(row -> text(row.get("post_report_id"))).toList())
        && actual.totalElements() == expected.size()
        && actual.totalPages() == (expected.isEmpty() ? 0 : ((expected.size() - 1) / size) + 1);
  }

  private static List<Map<String, Object>> expected(
      List<Map<String, Object>> rows, ReportQuery query) {
    return rows.stream()
        .filter(row -> query.status() == null || query.status().name().equals(text(row.get("status"))))
        .filter(row -> query.reportType() == null
            || query.reportType().name().equals(text(row.get("report_type"))))
        .filter(row -> query.targetType() == null
            || query.targetType().name().equals(text(row.get("target_type"))))
        .filter(row -> query.reporter() == null
            || containsIgnoreCase(text(row.get("reporter_username")), query.reporter()))
        .filter(row -> query.from() == null || (!instant(row.get("created_on")).isBefore(query.from())
            && !instant(row.get("created_on")).isAfter(query.to())))
        .sorted(Comparator.comparing((Map<String, Object> row) -> instant(row.get("created_on"))).reversed()
            .thenComparing(row -> text(row.get("post_report_id")), Comparator.reverseOrder()))
        .toList();
  }

  private static boolean containsIgnoreCase(String value, String search) {
    return value != null && value.toLowerCase(Locale.ROOT)
        .contains(search.strip().toLowerCase(Locale.ROOT));
  }

  private static Settings settings(String schema) {
    var prefix = prefix(schema, "social");
    return new Settings().withRenderMapping(new RenderMapping().withSchemata(
        new MappedSchema().withInput("social").withOutput(prefix + "social"),
        new MappedSchema().withInput("identity").withOutput(prefix + "identity")));
  }

  private static String prefix(String schema, String suffix) {
    if (!schema.endsWith(suffix)) {
      throw new IllegalArgumentException("Unexpected PostgreSQL schema.");
    }
    return schema.substring(0, schema.length() - suffix.length());
  }

  private static Instant instant(Object value) {
    return (Instant) value;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}

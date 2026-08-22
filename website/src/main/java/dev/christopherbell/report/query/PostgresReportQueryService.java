package dev.christopherbell.report.query;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.report.PostgresReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL stable report queue query. */
@PostgresPersistence
public class PostgresReportQueryService implements ReportQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_REPORTER_LENGTH = 100;
  private final JdbcClient database;
  private final PostgresReportRepository reports;
  private final String table;

  public PostgresReportQueryService(
      JdbcClient database, PostgresqlSchemaNames schemas, PostgresReportRepository reports) {
    this.database = database;
    this.reports = reports;
    table = schemas.qualifiedTable("social", "post_report");
  }

  @Override
  public ReportPage query(ReportQuery request) throws InvalidRequestException {
    validate(request);
    var clauses = new ArrayList<String>();
    var parameters = new HashMap<String, Object>();
    if (request.status() != null) {
      clauses.add("status = :status"); parameters.put("status", request.status().name());
    }
    if (request.reportType() != null) {
      clauses.add("report_type = :reportType"); parameters.put("reportType", request.reportType().name());
    }
    if (request.targetType() != null) {
      clauses.add("target_type = :targetType"); parameters.put("targetType", request.targetType().name());
    }
    if (request.reporter() != null && !request.reporter().isBlank()) {
      clauses.add("lower(reporter_username) like :reporter escape '\\'");
      parameters.put("reporter", "%" + escapeLike(
          request.reporter().strip().toLowerCase(java.util.Locale.ROOT)) + "%");
    }
    if (request.from() != null) {
      clauses.add("created_on between :from and :to");
      parameters.put("from", request.from().atOffset(ZoneOffset.UTC));
      parameters.put("to", request.to().atOffset(ZoneOffset.UTC));
    }
    String where = clauses.isEmpty() ? "true" : String.join(" and ", clauses);
    long total = statement("select count(*) from %s where %s".formatted(table, where), parameters)
        .query(Long.class).single();
    var rows = statement("""
            select * from %s where %s order by created_on desc, post_report_id desc
            limit :limit offset :offset
            """.formatted(table, where), parameters)
        .param("limit", request.size()).param("offset", Math.multiplyExact(request.page(), request.size()))
        .query(PostgresReportRepository::row).list();
    var mapped = reports.mapAll(rows);
    includeRepeatReportContext(mapped);
    int pages = total == 0 ? 0 : Math.toIntExact(((total - 1) / request.size()) + 1);
    return new ReportPage(mapped, request.page(), request.size(), total, pages);
  }

  private JdbcClient.StatementSpec statement(String sql, HashMap<String, Object> parameters) {
    var statement = database.sql(sql);
    for (var entry : parameters.entrySet()) statement.param(entry.getKey(), entry.getValue());
    return statement;
  }

  private void includeRepeatReportContext(List<PostReport> items) {
    var ids = items.stream().map(PostReport::getReportedAccountId)
        .filter(id -> id != null && !id.isBlank()).collect(java.util.stream.Collectors.toSet());
    var counts = new HashMap<String, long[]>();
    if (!ids.isEmpty()) {
      database.sql("""
              select reported_account_id, status, count(*) as report_count from %s
              where reported_account_id in (:ids) group by reported_account_id, status
              """.formatted(table)).param("ids", ids).query((row, ignored) -> {
            var values = counts.computeIfAbsent(row.getString("reported_account_id"), key -> new long[2]);
            if (ReportStatus.OPEN.name().equals(row.getString("status"))) values[0] = row.getLong("report_count");
            if (ReportStatus.RESOLVED.name().equals(row.getString("status"))) values[1] = row.getLong("report_count");
            return 0;
          }).list();
    }
    for (var report : items) {
      var id = report.getReportedAccountId();
      if (id == null || id.isBlank()) continue;
      var values = counts.getOrDefault(id, new long[2]);
      report.setOpenReportsForAccount(values[0]);
      report.setResolvedReportsForAccount(values[1]);
    }
  }

  private static void validate(ReportQuery request) throws InvalidRequestException {
    if (request == null || request.page() < 0 || request.size() < 1 || request.size() > MAX_PAGE_SIZE) {
      throw new InvalidRequestException("Invalid report page bounds.");
    }
    if (request.reporter() != null && request.reporter().strip().length() > MAX_REPORTER_LENGTH) {
      throw new InvalidRequestException("Invalid report reporter filter.");
    }
    if ((request.from() == null) != (request.to() == null)
        || (request.from() != null && request.from().isAfter(request.to()))) {
      throw new InvalidRequestException("Invalid report date range.");
    }
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}

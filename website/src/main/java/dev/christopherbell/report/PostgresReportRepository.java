package dev.christopherbell.report;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.moderation.ModerationAuditCommand;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL implementation of the post-report persistence port. */
@PostgresPersistence
public class PostgresReportRepository implements ReportRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String reportTable;
  private final String auditTable;
  private final String valueTable;

  public PostgresReportRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    reportTable = schemas.qualifiedTable("social", "post_report");
    auditTable = schemas.qualifiedTable("social", "post_report_moderation_audit");
    valueTable = schemas.qualifiedTable("social", "post_report_moderation_audit_value");
  }

  @Override
  public PostReport save(PostReport report) {
    var saved = transactions.execute(ignored -> {
      database.sql("""
              insert into %s (
                post_report_id, post_id, post_text, reported_account_id, reported_username,
                reporter_account_id, reporter_username, open_dedupe_key, report_type,
                target_type, reason, details, status, resolution, resolved_by,
                open_reports_for_account, resolved_reports_for_account,
                created_on, last_updated_on, resolved_on)
              values (:id, :postId, :postText, :reportedId, :reportedUsername,
                :reporterId, :reporterUsername, :dedupe, :reportType, :targetType,
                :reason, :details, :status, :resolution, :resolvedBy,
                :openCount, :resolvedCount, :createdOn, :updatedOn, :resolvedOn)
              on conflict (post_report_id) do update set
                post_id = excluded.post_id, post_text = excluded.post_text,
                reported_account_id = excluded.reported_account_id,
                reported_username = excluded.reported_username,
                reporter_account_id = excluded.reporter_account_id,
                reporter_username = excluded.reporter_username,
                open_dedupe_key = excluded.open_dedupe_key,
                report_type = excluded.report_type, target_type = excluded.target_type,
                reason = excluded.reason, details = excluded.details, status = excluded.status,
                resolution = excluded.resolution, resolved_by = excluded.resolved_by,
                open_reports_for_account = excluded.open_reports_for_account,
                resolved_reports_for_account = excluded.resolved_reports_for_account,
                last_updated_on = excluded.last_updated_on, resolved_on = excluded.resolved_on,
                version = %s.version + 1
              """.formatted(reportTable, reportTable)).paramSource(parameters(report)).update();
      replaceModerationAudit(report);
      return findById(report.getId()).orElseThrow();
    });
    if (saved == null) throw new IllegalStateException("Report transaction returned no value.");
    return saved;
  }

  @Override
  public Optional<PostReport> findById(String id) {
    return mapAll(database.sql("select * from %s where post_report_id = :id".formatted(reportTable))
        .param("id", id).query(PostgresReportRepository::row).list()).stream().findFirst();
  }

  private void replaceModerationAudit(PostReport report) {
    database.sql("delete from %s where post_report_id = :id".formatted(auditTable))
        .param("id", report.getId()).update();
    var audit = report.getPendingModerationAudit();
    if (audit == null) return;
    database.sql("""
            insert into %s (
              post_report_id, event_id, actor_account_id, actor_username, action,
              target_type, target_id, target_label, reason, message)
            values (:id, :eventId, :actorId, :actorUsername, :action,
              :targetType, :targetId, :targetLabel, :reason, :message)
            """.formatted(auditTable)).param("id", report.getId())
        .param("eventId", audit.eventId()).param("actorId", audit.actorAccountId())
        .param("actorUsername", audit.actorUsername()).param("action", audit.action())
        .param("targetType", audit.targetType()).param("targetId", audit.targetId())
        .param("targetLabel", audit.targetLabel()).param("reason", audit.reason())
        .param("message", audit.message()).update();
    insertValues(report.getId(), "before", audit.beforeValues());
    insertValues(report.getId(), "after", audit.afterValues());
    insertValues(report.getId(), "metadata", audit.metadata());
  }

  private void insertValues(String reportId, String partition, Map<String, String> values) {
    values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
        database.sql("""
                insert into %s (post_report_id, partition_name, value_key, value)
                values (:id, :partition, :key, :value)
                """.formatted(valueTable)).param("id", reportId).param("partition", partition)
            .param("key", entry.getKey()).param("value", entry.getValue()).update());
  }

  @Override
  public List<PostReport> findByStatusOrderByCreatedOnDesc(ReportStatus status) {
    return mapAll(database.sql("""
            select * from %s where status = :status
            order by created_on desc, post_report_id desc
            """.formatted(reportTable)).param("status", status.name())
        .query(PostgresReportRepository::row).list());
  }

  @Override public List<PostReport> findAllByOrderByCreatedOnDesc() {
    return findAllByOrderByCreatedOnDesc(Pageable.unpaged());
  }

  @Override
  public List<PostReport> findAllByOrderByCreatedOnDesc(Pageable pageable) {
    var statement = database.sql("""
            select * from %s order by created_on desc, post_report_id desc
            limit :limit offset :offset
            """.formatted(reportTable))
        .param("limit", pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE)
        .param("offset", pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0);
    return mapAll(statement.query(PostgresReportRepository::row).list());
  }

  @Override
  public Optional<PostReport> findByOpenDedupeKey(String key) {
    return mapAll(database.sql("select * from %s where open_dedupe_key = :key".formatted(reportTable))
        .param("key", key).query(PostgresReportRepository::row).list()).stream().findFirst();
  }

  @Override
  public Optional<PostReport> findFirstByReporterAccountIdAndPostIdAndStatus(
      String reporterId, String postId, ReportStatus status) {
    return mapAll(database.sql("""
            select * from %s where reporter_account_id = :reporterId
              and post_id = :postId and status = :status limit 1
            """.formatted(reportTable)).param("reporterId", reporterId).param("postId", postId)
        .param("status", status.name()).query(PostgresReportRepository::row).list())
        .stream().findFirst();
  }

  @Override
  public long countByReportedAccountIdAndStatus(String accountId, ReportStatus status) {
    return database.sql("""
            select count(*) from %s where reported_account_id = :accountId and status = :status
            """.formatted(reportTable)).param("accountId", accountId).param("status", status.name())
        .query(Long.class).single();
  }

  public List<PostReport> mapAll(List<ReportRow> rows) {
    if (rows.isEmpty()) return List.of();
    var ids = rows.stream().map(ReportRow::id).toList();
    var audits = database.sql("select * from %s where post_report_id in (:ids)".formatted(auditTable))
        .param("ids", ids).query(PostgresReportRepository::audit).list().stream()
        .collect(java.util.stream.Collectors.toMap(AuditRow::reportId, value -> value));
    var values = new HashMap<AuditPartition, LinkedHashMap<String, String>>();
    database.sql("""
            select * from %s where post_report_id in (:ids)
            order by post_report_id, partition_name, value_key
            """.formatted(valueTable)).param("ids", ids).query((value, ignored) -> {
          values.computeIfAbsent(new AuditPartition(
              value.getString("post_report_id"), value.getString("partition_name")),
              key -> new LinkedHashMap<>()).put(value.getString("value_key"), value.getString("value"));
          return 0;
        }).list();
    return rows.stream().map(row -> map(row, moderationAudit(audits.get(row.id()), values))).toList();
  }

  public static ReportRow row(ResultSet row, int ignored) throws SQLException {
    return new ReportRow(row.getString("post_report_id"), row.getString("post_id"),
        row.getString("post_text"), row.getString("reported_account_id"),
        row.getString("reported_username"), row.getString("reporter_account_id"),
        row.getString("reporter_username"), row.getString("open_dedupe_key"),
        row.getString("report_type"), row.getString("target_type"), row.getString("reason"),
        row.getString("details"), row.getString("status"), row.getString("resolution"),
        row.getString("resolved_by"), (Long) row.getObject("open_reports_for_account"),
        (Long) row.getObject("resolved_reports_for_account"), instant(row, "created_on"),
        instant(row, "last_updated_on"), instant(row, "resolved_on"));
  }

  private static AuditRow audit(ResultSet row, int ignored) throws SQLException {
    return new AuditRow(row.getString("post_report_id"), row.getString("event_id"),
        row.getString("actor_account_id"), row.getString("actor_username"),
        row.getString("action"), row.getString("target_type"), row.getString("target_id"),
        row.getString("target_label"), row.getString("reason"), row.getString("message"));
  }

  private static PostReport map(ReportRow row, ModerationAuditCommand audit) {
    return PostReport.builder().id(row.id()).postId(row.postId()).postText(row.postText())
        .reportedAccountId(row.reportedId()).reportedUsername(row.reportedUsername())
        .reporterAccountId(row.reporterId()).reporterUsername(row.reporterUsername())
        .openDedupeKey(row.dedupe()).reportType(ReportType.valueOf(row.reportType()))
        .targetType(ReportTargetType.valueOf(row.targetType())).reason(row.reason())
        .details(row.details()).status(ReportStatus.valueOf(row.status()))
        .resolution(row.resolution() == null ? null : ReportResolution.valueOf(row.resolution()))
        .resolvedBy(row.resolvedBy()).openReportsForAccount(row.openCount())
        .resolvedReportsForAccount(row.resolvedCount()).pendingModerationAudit(audit)
        .createdOn(row.createdOn()).lastUpdatedOn(row.updatedOn()).resolvedOn(row.resolvedOn()).build();
  }

  private static ModerationAuditCommand moderationAudit(
      AuditRow audit, Map<AuditPartition, LinkedHashMap<String, String>> values) {
    if (audit == null) return null;
    return new ModerationAuditCommand(audit.eventId(), audit.actorId(), audit.actorUsername(),
        audit.action(), audit.targetType(), audit.targetId(), audit.targetLabel(), audit.reason(),
        audit.message(), auditValues(values, audit.reportId(), "before"),
        auditValues(values, audit.reportId(), "after"),
        auditValues(values, audit.reportId(), "metadata"));
  }

  private static Map<String, String> auditValues(
      Map<AuditPartition, LinkedHashMap<String, String>> values, String id, String partition) {
    return Map.copyOf(values.getOrDefault(new AuditPartition(id, partition), new LinkedHashMap<>()));
  }

  private static MapSqlParameterSource parameters(PostReport report) {
    return new MapSqlParameterSource().addValue("id", report.getId())
        .addValue("postId", report.getPostId(), Types.VARCHAR)
        .addValue("postText", report.getPostText(), Types.VARCHAR)
        .addValue("reportedId", report.getReportedAccountId(), Types.VARCHAR)
        .addValue("reportedUsername", report.getReportedUsername(), Types.VARCHAR)
        .addValue("reporterId", report.getReporterAccountId())
        .addValue("reporterUsername", report.getReporterUsername())
        .addValue("dedupe", report.getOpenDedupeKey(), Types.VARCHAR)
        .addValue("reportType", report.getReportType().name())
        .addValue("targetType", report.getTargetType().name()).addValue("reason", report.getReason())
        .addValue("details", report.getDetails(), Types.VARCHAR)
        .addValue("status", report.getStatus().name())
        .addValue("resolution", report.getResolution() == null ? null : report.getResolution().name(),
            Types.VARCHAR).addValue("resolvedBy", report.getResolvedBy(), Types.VARCHAR)
        .addValue("openCount", report.getOpenReportsForAccount())
        .addValue("resolvedCount", report.getResolvedReportsForAccount())
        .addValue("createdOn", timestamp(report.getCreatedOn()))
        .addValue("updatedOn", timestamp(report.getLastUpdatedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("resolvedOn", timestamp(report.getResolvedOn()), Types.TIMESTAMP_WITH_TIMEZONE);
  }

  private static OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record ReportRow(
      String id, String postId, String postText, String reportedId, String reportedUsername,
      String reporterId, String reporterUsername, String dedupe, String reportType,
      String targetType, String reason, String details, String status, String resolution,
      String resolvedBy, Long openCount, Long resolvedCount, Instant createdOn,
      Instant updatedOn, Instant resolvedOn) {}
  private record AuditRow(
      String reportId, String eventId, String actorId, String actorUsername, String action,
      String targetType, String targetId, String targetLabel, String reason, String message) {}
  private record AuditPartition(String reportId, String partition) {}
}

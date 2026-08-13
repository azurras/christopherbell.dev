package dev.christopherbell.report;

import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT_MODERATION_AUDIT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT_MODERATION_AUDIT_VALUE;

import dev.christopherbell.admin.activity.ModerationAuditCommand;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.social.tables.records.PostReportRecord;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportResolution;
import dev.christopherbell.report.model.ReportStatus;
import dev.christopherbell.report.model.ReportTargetType;
import dev.christopherbell.report.model.ReportType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Pageable;

/** PostgreSQL implementation of the post-report persistence port. */
@PostgresPersistence
public final class PostgresReportRepository implements ReportRepository {
  private final DSLContext database;

  public PostgresReportRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public PostReport save(PostReport report) {
    return database.transactionResult(configuration -> save(DSL.using(configuration), report));
  }

  private static PostReport save(DSLContext transaction, PostReport report) {
    var insert = transaction.insertInto(POST_REPORT)
        .set(POST_REPORT.POST_REPORT_ID, report.getId())
        .set(POST_REPORT.POST_ID, report.getPostId())
        .set(POST_REPORT.POST_TEXT, report.getPostText())
        .set(POST_REPORT.REPORTED_ACCOUNT_ID, report.getReportedAccountId())
        .set(POST_REPORT.REPORTED_USERNAME, report.getReportedUsername())
        .set(POST_REPORT.REPORTER_ACCOUNT_ID, report.getReporterAccountId())
        .set(POST_REPORT.REPORTER_USERNAME, report.getReporterUsername())
        .set(POST_REPORT.OPEN_DEDUPE_KEY, report.getOpenDedupeKey())
        .set(POST_REPORT.REPORT_TYPE, report.getReportType().name())
        .set(POST_REPORT.TARGET_TYPE, report.getTargetType().name())
        .set(POST_REPORT.REASON, report.getReason())
        .set(POST_REPORT.DETAILS, report.getDetails())
        .set(POST_REPORT.STATUS, report.getStatus().name())
        .set(POST_REPORT.RESOLUTION,
            report.getResolution() == null ? null : report.getResolution().name())
        .set(POST_REPORT.RESOLVED_BY, report.getResolvedBy())
        .set(POST_REPORT.OPEN_REPORTS_FOR_ACCOUNT, report.getOpenReportsForAccount())
        .set(POST_REPORT.RESOLVED_REPORTS_FOR_ACCOUNT, report.getResolvedReportsForAccount())
        .set(POST_REPORT.CREATED_ON, report.getCreatedOn().atOffset(ZoneOffset.UTC))
        .set(POST_REPORT.LAST_UPDATED_ON, timestamp(report.getLastUpdatedOn()))
        .set(POST_REPORT.RESOLVED_ON, timestamp(report.getResolvedOn()));
    insert.onConflict(POST_REPORT.POST_REPORT_ID).doUpdate()
        .set(POST_REPORT.POST_ID, report.getPostId())
        .set(POST_REPORT.POST_TEXT, report.getPostText())
        .set(POST_REPORT.REPORTED_ACCOUNT_ID, report.getReportedAccountId())
        .set(POST_REPORT.REPORTED_USERNAME, report.getReportedUsername())
        .set(POST_REPORT.REPORTER_ACCOUNT_ID, report.getReporterAccountId())
        .set(POST_REPORT.REPORTER_USERNAME, report.getReporterUsername())
        .set(POST_REPORT.OPEN_DEDUPE_KEY, report.getOpenDedupeKey())
        .set(POST_REPORT.REPORT_TYPE, report.getReportType().name())
        .set(POST_REPORT.TARGET_TYPE, report.getTargetType().name())
        .set(POST_REPORT.REASON, report.getReason())
        .set(POST_REPORT.DETAILS, report.getDetails())
        .set(POST_REPORT.STATUS, report.getStatus().name())
        .set(POST_REPORT.RESOLUTION,
            report.getResolution() == null ? null : report.getResolution().name())
        .set(POST_REPORT.RESOLVED_BY, report.getResolvedBy())
        .set(POST_REPORT.OPEN_REPORTS_FOR_ACCOUNT, report.getOpenReportsForAccount())
        .set(POST_REPORT.RESOLVED_REPORTS_FOR_ACCOUNT, report.getResolvedReportsForAccount())
        .set(POST_REPORT.LAST_UPDATED_ON, timestamp(report.getLastUpdatedOn()))
        .set(POST_REPORT.RESOLVED_ON, timestamp(report.getResolvedOn()))
        .set(POST_REPORT.VERSION, POST_REPORT.VERSION.plus(1L))
        .execute();
    replaceModerationAudit(transaction, report);
    return findById(transaction, report.getId()).orElseThrow();
  }

  @Override
  public Optional<PostReport> findById(String id) {
    return findById(database, id);
  }

  private static Optional<PostReport> findById(DSLContext context, String id) {
    return context.selectFrom(POST_REPORT).where(POST_REPORT.POST_REPORT_ID.eq(id))
        .fetchOptional(record -> map(context, record));
  }

  private static void replaceModerationAudit(DSLContext transaction, PostReport report) {
    transaction.deleteFrom(POST_REPORT_MODERATION_AUDIT)
        .where(POST_REPORT_MODERATION_AUDIT.POST_REPORT_ID.eq(report.getId()))
        .execute();
    var audit = report.getPendingModerationAudit();
    if (audit == null) return;
    transaction.insertInto(POST_REPORT_MODERATION_AUDIT)
        .set(POST_REPORT_MODERATION_AUDIT.POST_REPORT_ID, report.getId())
        .set(POST_REPORT_MODERATION_AUDIT.EVENT_ID, audit.eventId())
        .set(POST_REPORT_MODERATION_AUDIT.ACTOR_ACCOUNT_ID, audit.actorAccountId())
        .set(POST_REPORT_MODERATION_AUDIT.ACTOR_USERNAME, audit.actorUsername())
        .set(POST_REPORT_MODERATION_AUDIT.ACTION, audit.action())
        .set(POST_REPORT_MODERATION_AUDIT.TARGET_TYPE, audit.targetType())
        .set(POST_REPORT_MODERATION_AUDIT.TARGET_ID, audit.targetId())
        .set(POST_REPORT_MODERATION_AUDIT.TARGET_LABEL, audit.targetLabel())
        .set(POST_REPORT_MODERATION_AUDIT.REASON, audit.reason())
        .set(POST_REPORT_MODERATION_AUDIT.MESSAGE, audit.message())
        .execute();
    insertAuditValues(transaction, report.getId(), "before", audit.beforeValues());
    insertAuditValues(transaction, report.getId(), "after", audit.afterValues());
    insertAuditValues(transaction, report.getId(), "metadata", audit.metadata());
  }

  private static void insertAuditValues(
      DSLContext transaction, String reportId, String partition, Map<String, String> values) {
    if (values.isEmpty()) return;
    var insert = transaction.insertInto(
        POST_REPORT_MODERATION_AUDIT_VALUE,
        POST_REPORT_MODERATION_AUDIT_VALUE.POST_REPORT_ID,
        POST_REPORT_MODERATION_AUDIT_VALUE.PARTITION_NAME,
        POST_REPORT_MODERATION_AUDIT_VALUE.VALUE_KEY,
        POST_REPORT_MODERATION_AUDIT_VALUE.VALUE);
    for (var entry : values.entrySet()) {
      insert = insert.values(reportId, partition, entry.getKey(), entry.getValue());
    }
    insert.execute();
  }

  @Override
  public List<PostReport> findByStatusOrderByCreatedOnDesc(ReportStatus status) {
    return database.selectFrom(POST_REPORT).where(POST_REPORT.STATUS.eq(status.name()))
        .orderBy(POST_REPORT.CREATED_ON.desc(), POST_REPORT.POST_REPORT_ID.desc())
        .fetch(record -> map(database, record));
  }

  @Override
  public List<PostReport> findAllByOrderByCreatedOnDesc() {
    return findAllByOrderByCreatedOnDesc(Pageable.unpaged());
  }

  @Override
  public List<PostReport> findAllByOrderByCreatedOnDesc(Pageable pageable) {
    var query = database.selectFrom(POST_REPORT)
        .orderBy(POST_REPORT.CREATED_ON.desc(), POST_REPORT.POST_REPORT_ID.desc());
    return pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch(record -> map(database, record))
        : query.fetch(record -> map(database, record));
  }

  @Override
  public Optional<PostReport> findByOpenDedupeKey(String openDedupeKey) {
    return database.selectFrom(POST_REPORT).where(POST_REPORT.OPEN_DEDUPE_KEY.eq(openDedupeKey))
        .fetchOptional(record -> map(database, record));
  }

  @Override
  public Optional<PostReport> findFirstByReporterAccountIdAndPostIdAndStatus(
      String reporterAccountId, String postId, ReportStatus status) {
    return database.selectFrom(POST_REPORT)
        .where(POST_REPORT.REPORTER_ACCOUNT_ID.eq(reporterAccountId)
            .and(POST_REPORT.POST_ID.eq(postId)).and(POST_REPORT.STATUS.eq(status.name())))
        .limit(1).fetchOptional(record -> map(database, record));
  }

  @Override
  public long countByReportedAccountIdAndStatus(String reportedAccountId, ReportStatus status) {
    return database.fetchCount(POST_REPORT,
        POST_REPORT.REPORTED_ACCOUNT_ID.eq(reportedAccountId)
            .and(POST_REPORT.STATUS.eq(status.name())));
  }

  public static PostReport map(DSLContext context, PostReportRecord record) {
    var audit = context.selectFrom(POST_REPORT_MODERATION_AUDIT)
        .where(POST_REPORT_MODERATION_AUDIT.POST_REPORT_ID.eq(record.getPostReportId()))
        .fetchOptional(value -> new ModerationAuditCommand(
            value.getEventId(), value.getActorAccountId(), value.getActorUsername(),
            value.getAction(), value.getTargetType(), value.getTargetId(), value.getTargetLabel(),
            value.getReason(), value.getMessage(),
            auditValues(context, record.getPostReportId(), "before"),
            auditValues(context, record.getPostReportId(), "after"),
            auditValues(context, record.getPostReportId(), "metadata")))
        .orElse(null);
    return PostReport.builder()
        .id(record.getPostReportId())
        .postId(record.getPostId())
        .postText(record.getPostText())
        .reportedAccountId(record.getReportedAccountId())
        .reportedUsername(record.getReportedUsername())
        .reporterAccountId(record.getReporterAccountId())
        .reporterUsername(record.getReporterUsername())
        .openDedupeKey(record.getOpenDedupeKey())
        .reportType(ReportType.valueOf(record.getReportType()))
        .targetType(ReportTargetType.valueOf(record.getTargetType()))
        .reason(record.getReason())
        .details(record.getDetails())
        .status(ReportStatus.valueOf(record.getStatus()))
        .resolution(record.getResolution() == null
            ? null : ReportResolution.valueOf(record.getResolution()))
        .resolvedBy(record.getResolvedBy())
        .openReportsForAccount(record.getOpenReportsForAccount())
        .resolvedReportsForAccount(record.getResolvedReportsForAccount())
        .pendingModerationAudit(audit)
        .createdOn(record.getCreatedOn().toInstant())
        .lastUpdatedOn(instant(record.getLastUpdatedOn()))
        .resolvedOn(instant(record.getResolvedOn()))
        .build();
  }

  private static Map<String, String> auditValues(
      DSLContext context, String reportId, String partition) {
    var values = new LinkedHashMap<String, String>();
    context.select(
            POST_REPORT_MODERATION_AUDIT_VALUE.VALUE_KEY,
            POST_REPORT_MODERATION_AUDIT_VALUE.VALUE)
        .from(POST_REPORT_MODERATION_AUDIT_VALUE)
        .where(POST_REPORT_MODERATION_AUDIT_VALUE.POST_REPORT_ID.eq(reportId)
            .and(POST_REPORT_MODERATION_AUDIT_VALUE.PARTITION_NAME.eq(partition)))
        .orderBy(POST_REPORT_MODERATION_AUDIT_VALUE.VALUE_KEY.asc())
        .forEach(row -> values.put(row.value1(), row.value2()));
    return Map.copyOf(values);
  }

  private static OffsetDateTime timestamp(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

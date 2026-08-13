package dev.christopherbell.report;

import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT_MODERATION_AUDIT;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT_MODERATION_AUDIT_VALUE;

import dev.christopherbell.admin.activity.ModerationAuditCommand;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.social.tables.records.PostReportRecord;
import dev.christopherbell.persistence.jooq.social.tables.records.PostReportModerationAuditRecord;
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
import java.util.HashMap;
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
    var records = database.selectFrom(POST_REPORT).where(POST_REPORT.STATUS.eq(status.name()))
        .orderBy(POST_REPORT.CREATED_ON.desc(), POST_REPORT.POST_REPORT_ID.desc())
        .fetch();
    return mapAll(database, records);
  }

  @Override
  public List<PostReport> findAllByOrderByCreatedOnDesc() {
    return findAllByOrderByCreatedOnDesc(Pageable.unpaged());
  }

  @Override
  public List<PostReport> findAllByOrderByCreatedOnDesc(Pageable pageable) {
    var query = database.selectFrom(POST_REPORT)
        .orderBy(POST_REPORT.CREATED_ON.desc(), POST_REPORT.POST_REPORT_ID.desc());
    var records = pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch()
        : query.fetch();
    return mapAll(database, records);
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
    return mapAll(context, List.of(record)).getFirst();
  }

  public static List<PostReport> mapAll(DSLContext context, List<PostReportRecord> records) {
    if (records.isEmpty()) return List.of();
    var reportIds = records.stream().map(PostReportRecord::getPostReportId).toList();
    Map<String, PostReportModerationAuditRecord> audits = context
        .selectFrom(POST_REPORT_MODERATION_AUDIT)
        .where(POST_REPORT_MODERATION_AUDIT.POST_REPORT_ID.in(reportIds))
        .fetchMap(POST_REPORT_MODERATION_AUDIT.POST_REPORT_ID);
    var values = new HashMap<AuditPartition, LinkedHashMap<String, String>>();
    context.selectFrom(POST_REPORT_MODERATION_AUDIT_VALUE)
        .where(POST_REPORT_MODERATION_AUDIT_VALUE.POST_REPORT_ID.in(reportIds))
        .orderBy(POST_REPORT_MODERATION_AUDIT_VALUE.POST_REPORT_ID.asc(),
            POST_REPORT_MODERATION_AUDIT_VALUE.PARTITION_NAME.asc(),
            POST_REPORT_MODERATION_AUDIT_VALUE.VALUE_KEY.asc())
        .forEach(row -> values.computeIfAbsent(
            new AuditPartition(row.getPostReportId(), row.getPartitionName()),
            ignored -> new LinkedHashMap<>()).put(row.getValueKey(), row.getValue()));
    return records.stream().map(record -> map(record, moderationAudit(
        audits.get(record.getPostReportId()), values))).toList();
  }

  private static PostReport map(PostReportRecord record, ModerationAuditCommand audit) {
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

  private static ModerationAuditCommand moderationAudit(
      PostReportModerationAuditRecord audit,
      Map<AuditPartition, LinkedHashMap<String, String>> values) {
    if (audit == null) return null;
    return new ModerationAuditCommand(
        audit.getEventId(), audit.getActorAccountId(), audit.getActorUsername(),
        audit.getAction(), audit.getTargetType(), audit.getTargetId(), audit.getTargetLabel(),
        audit.getReason(), audit.getMessage(),
        auditValues(values, audit.getPostReportId(), "before"),
        auditValues(values, audit.getPostReportId(), "after"),
        auditValues(values, audit.getPostReportId(), "metadata"));
  }

  private static Map<String, String> auditValues(
      Map<AuditPartition, LinkedHashMap<String, String>> values,
      String reportId,
      String partition) {
    return Map.copyOf(values.getOrDefault(
        new AuditPartition(reportId, partition), new LinkedHashMap<>()));
  }

  private static OffsetDateTime timestamp(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private record AuditPartition(String reportId, String partition) {}
}

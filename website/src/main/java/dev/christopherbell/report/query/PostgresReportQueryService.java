package dev.christopherbell.report.query;

import static dev.christopherbell.persistence.jooq.social.Tables.POST_REPORT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.report.PostgresReportRepository;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL stable report queue query. */
@PostgresPersistence
public class PostgresReportQueryService implements ReportQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_REPORTER_LENGTH = 100;
  private final DSLContext database;
  private final ReportRepository reports;

  public PostgresReportQueryService(DSLContext database, ReportRepository reports) {
    this.database = database;
    this.reports = reports;
  }

  @Override
  public ReportPage query(ReportQuery request) throws InvalidRequestException {
    validate(request);
    var condition = condition(request);
    long total = database.fetchCount(POST_REPORT, condition);
    var items = database.selectFrom(POST_REPORT)
        .where(condition)
        .orderBy(POST_REPORT.CREATED_ON.desc(), POST_REPORT.POST_REPORT_ID.desc())
        .limit(request.size())
        .offset(Math.multiplyExact(request.page(), request.size()))
        .fetch();
    var mapped = PostgresReportRepository.mapAll(database, items);
    includeRepeatReportContext(mapped);
    int pages = total == 0 ? 0 : Math.toIntExact(((total - 1) / request.size()) + 1);
    return new ReportPage(mapped, request.page(), request.size(), total, pages);
  }

  private static Condition condition(ReportQuery request) {
    var condition = DSL.noCondition();
    if (request.status() != null) condition = condition.and(POST_REPORT.STATUS.eq(request.status().name()));
    if (request.reportType() != null) condition = condition.and(POST_REPORT.REPORT_TYPE.eq(request.reportType().name()));
    if (request.targetType() != null) condition = condition.and(POST_REPORT.TARGET_TYPE.eq(request.targetType().name()));
    if (request.reporter() != null && !request.reporter().isBlank()) {
      var literal = "%" + escapeLike(request.reporter().strip().toLowerCase(java.util.Locale.ROOT)) + "%";
      condition = condition.and(DSL.lower(POST_REPORT.REPORTER_USERNAME).like(literal, '\\'));
    }
    if (request.from() != null) {
      condition = condition.and(POST_REPORT.CREATED_ON.between(
          request.from().atOffset(ZoneOffset.UTC), request.to().atOffset(ZoneOffset.UTC)));
    }
    return condition;
  }

  private void includeRepeatReportContext(List<PostReport> items) {
    var accountIds = items.stream().map(PostReport::getReportedAccountId)
        .filter(id -> id != null && !id.isBlank()).collect(java.util.stream.Collectors.toSet());
    var counts = new HashMap<String, long[]>();
    if (!accountIds.isEmpty()) {
      database.select(POST_REPORT.REPORTED_ACCOUNT_ID, POST_REPORT.STATUS, DSL.count())
          .from(POST_REPORT)
          .where(POST_REPORT.REPORTED_ACCOUNT_ID.in(accountIds))
          .groupBy(POST_REPORT.REPORTED_ACCOUNT_ID, POST_REPORT.STATUS)
          .forEach(row -> {
            var values = counts.computeIfAbsent(row.value1(), ignored -> new long[2]);
            if (ReportStatus.OPEN.name().equals(row.value2())) values[0] = row.value3();
            if (ReportStatus.RESOLVED.name().equals(row.value2())) values[1] = row.value3();
          });
    }
    for (var report : items) {
      var accountId = report.getReportedAccountId();
      if (accountId == null || accountId.isBlank()) continue;
      var values = counts.getOrDefault(accountId, new long[2]);
      report.setOpenReportsForAccount(values[0]);
      report.setResolvedReportsForAccount(values[1]);
    }
  }

  private static void validate(ReportQuery request) throws InvalidRequestException {
    if (request == null || request.page() < 0 || request.size() < 1
        || request.size() > MAX_PAGE_SIZE) {
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

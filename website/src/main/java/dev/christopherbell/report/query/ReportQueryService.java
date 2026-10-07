package dev.christopherbell.report.query;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Validates and executes filterable stable report queue pages. */
@MongoPersistence
public class ReportQueryService implements ReportQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_REPORTER_LENGTH = 100;
  private final KindScopedMongoOperations<PostReport> mongo;
  private final ReportRepository reports;

  public ReportQueryService(DomainMongoOperationsFactory factory, ReportRepository reports) {
    this.mongo = factory.forType(PostReport.class);
    this.reports = reports;
  }

  /** Returns a page ordered by immutable creation time and id tie-breaker. */
  @Override
  public ReportPage query(ReportQuery reportQuery) throws InvalidRequestException {
    validate(reportQuery);
    Criteria matchingReports = criteriaFor(reportQuery);
    long totalElements = mongo.count(new Query(matchingReports));
    Query pageQuery = new Query(matchingReports)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .skip((long) reportQuery.page() * reportQuery.size())
        .limit(reportQuery.size());
    List<PostReport> pageItems = mongo.find(pageQuery, Pageable.unpaged());
    includeRepeatReportCounts(pageItems);
    int totalPages = totalElements == 0
        ? 0
        : (int) Math.ceil((double) totalElements / reportQuery.size());
    return new ReportPage(
        pageItems, reportQuery.page(), reportQuery.size(), totalElements, totalPages);
  }

  private static Criteria criteriaFor(ReportQuery reportQuery) {
    List<Criteria> filters = new ArrayList<>();
    if (reportQuery.status() != null) {
      filters.add(Criteria.where("status").is(reportQuery.status()));
    }
    if (reportQuery.reportType() != null) {
      filters.add(Criteria.where("reportType").is(reportQuery.reportType()));
    }
    if (reportQuery.targetType() != null) {
      filters.add(Criteria.where("targetType").is(reportQuery.targetType()));
    }
    if (reportQuery.reporter() != null && !reportQuery.reporter().isBlank()) {
      Pattern reporterSubstring = Pattern.compile(
          Pattern.quote(reportQuery.reporter().strip()), Pattern.CASE_INSENSITIVE);
      filters.add(Criteria.where("reporterUsername").regex(reporterSubstring));
    }
    if (reportQuery.from() != null) {
      filters.add(Criteria.where("createdOn").gte(reportQuery.from()).lte(reportQuery.to()));
    }
    return filters.isEmpty()
        ? new Criteria()
        : new Criteria().andOperator(filters.toArray(Criteria[]::new));
  }

  /** Adds each reported account's open and resolved counts, counting each account once. */
  private void includeRepeatReportCounts(List<PostReport> pageItems) {
    Map<String, RepeatReportCounts> countsByReportedAccountId = new HashMap<>();
    for (PostReport report : pageItems) {
      String reportedAccountId = report.getReportedAccountId();
      if (reportedAccountId == null || reportedAccountId.isBlank()) {
        continue;
      }
      RepeatReportCounts repeatReportCounts =
          countsByReportedAccountId.computeIfAbsent(reportedAccountId, this::repeatReportCountsFor);
      report.setOpenReportsForAccount(repeatReportCounts.openCount());
      report.setResolvedReportsForAccount(repeatReportCounts.resolvedCount());
    }
  }

  private RepeatReportCounts repeatReportCountsFor(String reportedAccountId) {
    return new RepeatReportCounts(
        reports.countByReportedAccountIdAndStatus(reportedAccountId, ReportStatus.OPEN),
        reports.countByReportedAccountIdAndStatus(reportedAccountId, ReportStatus.RESOLVED));
  }

  private static void validate(ReportQuery reportQuery) throws InvalidRequestException {
    if (reportQuery == null || reportQuery.page() < 0 || reportQuery.size() < 1
        || reportQuery.size() > MAX_PAGE_SIZE) {
      throw new InvalidRequestException("Invalid report page bounds.");
    }
    if (reportQuery.reporter() != null
        && reportQuery.reporter().strip().length() > MAX_REPORTER_LENGTH) {
      throw new InvalidRequestException("Invalid report reporter filter.");
    }
    boolean hasOnlyOneDateBound = (reportQuery.from() == null) != (reportQuery.to() == null);
    if (hasOnlyOneDateBound
        || (reportQuery.from() != null && reportQuery.from().isAfter(reportQuery.to()))) {
      throw new InvalidRequestException("Invalid report date range.");
    }
  }

  private record RepeatReportCounts(long openCount, long resolvedCount) {
  }
}

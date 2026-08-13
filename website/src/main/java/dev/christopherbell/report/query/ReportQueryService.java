package dev.christopherbell.report.query;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.ReportRepository;
import dev.christopherbell.report.model.ReportStatus;
import java.util.ArrayList;
import java.util.regex.Pattern;
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
  public ReportPage query(ReportQuery request) throws InvalidRequestException {
    validate(request);
    var filters = new ArrayList<Criteria>();
    if (request.status() != null) filters.add(Criteria.where("status").is(request.status()));
    if (request.reportType() != null) {
      filters.add(Criteria.where("reportType").is(request.reportType()));
    }
    if (request.targetType() != null) {
      filters.add(Criteria.where("targetType").is(request.targetType()));
    }
    if (request.reporter() != null && !request.reporter().isBlank()) {
      filters.add(Criteria.where("reporterUsername")
          .regex(Pattern.compile(Pattern.quote(request.reporter().strip()), Pattern.CASE_INSENSITIVE)));
    }
    if (request.from() != null) {
      filters.add(Criteria.where("createdOn").gte(request.from()).lte(request.to()));
    }
    Criteria criteria = filters.isEmpty()
        ? new Criteria()
        : new Criteria().andOperator(filters.toArray(Criteria[]::new));
    var countQuery = new Query(criteria);
    long total = mongo.count(countQuery);
    var pageQuery = new Query(criteria)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .skip((long) request.page() * request.size())
        .limit(request.size());
    var items = mongo.find(pageQuery, org.springframework.data.domain.Pageable.unpaged());
    includeRepeatReportContext(items);
    int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / request.size());
    return new ReportPage(items, request.page(), request.size(), total, totalPages);
  }

  private void includeRepeatReportContext(java.util.List<PostReport> items) {
    var counts = new java.util.HashMap<String, long[]>();
    for (var report : items) {
      var accountId = report.getReportedAccountId();
      if (accountId == null || accountId.isBlank()) continue;
      var values = counts.computeIfAbsent(accountId, id -> new long[] {
          reports.countByReportedAccountIdAndStatus(id, ReportStatus.OPEN),
          reports.countByReportedAccountIdAndStatus(id, ReportStatus.RESOLVED)
      });
      report.setOpenReportsForAccount(values[0]);
      report.setResolvedReportsForAccount(values[1]);
    }
  }

  private void validate(ReportQuery request) throws InvalidRequestException {
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
}

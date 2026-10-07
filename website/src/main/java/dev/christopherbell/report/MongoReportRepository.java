package dev.christopherbell.report;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Stores post reports in the report domain collection. */
@MongoPersistence
@Repository
class MongoReportRepository extends KindScopedRepositorySupport<PostReport>
    implements ReportRepository {

  MongoReportRepository(DomainMongoOperationsFactory factory) {
    super(factory, PostReport.class);
  }

  @Override
  public PostReport save(PostReport report) {
    return saveValue(report);
  }

  @Override
  public Optional<PostReport> findById(String reportId) {
    return findValueById(reportId);
  }

  @Override
  public List<PostReport> findByStatusOrderByCreatedOnDesc(ReportStatus status) {
    return find(newestFirst(Query.query(Criteria.where("status").is(status))));
  }

  @Override
  public List<PostReport> findAllByOrderByCreatedOnDesc() {
    return find(newestFirst(new Query()));
  }

  @Override
  public List<PostReport> findAllByOrderByCreatedOnDesc(Pageable pageable) {
    return find(newestFirst(new Query()), pageable);
  }

  @Override
  public Optional<PostReport> findByOpenDedupeKey(String openDedupeKey) {
    return findOne(Query.query(Criteria.where("openDedupeKey").is(openDedupeKey)));
  }

  @Override
  public Optional<PostReport> findFirstByReporterAccountIdAndPostIdAndStatus(
      String reporterAccountId, String postId, ReportStatus status) {
    return findOne(Query.query(Criteria.where("reporterAccountId").is(reporterAccountId)
        .and("postId").is(postId)
        .and("status").is(status)));
  }

  @Override
  public long countByReportedAccountIdAndStatus(String reportedAccountId, ReportStatus status) {
    return mongo.count(Query.query(Criteria.where("reportedAccountId").is(reportedAccountId)
        .and("status").is(status)));
  }

  private static Query newestFirst(Query query) {
    return query.with(Sort.by(Sort.Direction.DESC, "createdOn"));
  }
}

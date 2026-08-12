package dev.christopherbell.report;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.report.model.PostReport;
import dev.christopherbell.report.model.ReportStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
final class MongoReportRepository extends KindScopedRepositorySupport<PostReport>
    implements ReportRepository {
  MongoReportRepository(DomainMongoOperationsFactory factory) { super(factory, PostReport.class); }
  @Override public PostReport save(PostReport value) { return saveValue(value); }
  @Override public Optional<PostReport> findById(String id) { return findValueById(id); }
  @Override public List<PostReport> findByStatusOrderByCreatedOnDesc(ReportStatus status) {
    return find(statusQuery(status));
  }
  @Override public List<PostReport> findAllByOrderByCreatedOnDesc() {
    return find(createdDescending());
  }
  @Override public List<PostReport> findAllByOrderByCreatedOnDesc(Pageable pageable) {
    return find(createdDescending(), pageable);
  }
  @Override public Optional<PostReport> findByOpenDedupeKey(String key) {
    return findOne(Query.query(Criteria.where("openDedupeKey").is(key)));
  }
  @Override
  public Optional<PostReport> findFirstByReporterAccountIdAndPostIdAndStatus(
      String reporterId, String postId, ReportStatus status) {
    return findOne(Query.query(Criteria.where("reporterAccountId").is(reporterId)
        .and("postId").is(postId).and("status").is(status)));
  }
  @Override public long countByReportedAccountIdAndStatus(String accountId, ReportStatus status) {
    return mongo.count(Query.query(Criteria.where("reportedAccountId").is(accountId)
        .and("status").is(status)));
  }
  private static Query statusQuery(ReportStatus status) {
    return Query.query(Criteria.where("status").is(status))
        .with(Sort.by(Sort.Direction.DESC, "createdOn"));
  }
  private static Query createdDescending() {
    return new Query().with(Sort.by(Sort.Direction.DESC, "createdOn"));
  }
}

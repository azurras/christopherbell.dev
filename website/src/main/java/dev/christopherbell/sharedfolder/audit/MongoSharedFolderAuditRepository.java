package dev.christopherbell.sharedfolder.audit;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
public class MongoSharedFolderAuditRepository
    extends KindScopedRepositorySupport<SharedFolderAuditEvent>
    implements SharedFolderAuditRepository {
  public MongoSharedFolderAuditRepository(DomainMongoOperationsFactory factory) { super(factory, SharedFolderAuditEvent.class); }
  @Override public SharedFolderAuditEvent save(SharedFolderAuditEvent value) { return saveValue(value); }

  @Override public int deleteExpired(Instant cutoff, int limit) {
    List<String> ids = find(Query.query(Criteria.where("expiresAt").lte(cutoff)),
        PageRequest.of(0, limit, Sort.by("expiresAt", "id"))).stream()
        .map(SharedFolderAuditEvent::id).toList();
    int deleted = 0;
    for (String id : ids) {
      if (mongo.remove(Query.query(Criteria.where("id").is(id)
          .and("expiresAt").lte(cutoff))).getDeletedCount() == 1) deleted++;
    }
    return deleted;
  }

  @Override public List<SharedFolderAuditEvent> search(
      String accountId, String action, String outcome, String relativePath,
      Instant from, Instant to, int limit) {
    var filters = new ArrayList<Criteria>();
    if (accountId != null) filters.add(Criteria.where("accountId").is(accountId));
    if (action != null) filters.add(Criteria.where("action").is(action));
    if (outcome != null) filters.add(Criteria.where("outcome").is(outcome));
    if (relativePath != null) filters.add(Criteria.where("relativePath").is(relativePath));
    if (from != null || to != null) {
      var occurred = Criteria.where("occurredAt");
      if (from != null) occurred.gte(from);
      if (to != null) occurred.lte(to);
      filters.add(occurred);
    }
    var query = filters.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(filters));
    return find(query, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "occurredAt")));
  }
}

package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
public class MongoSharedFolderMutationRecoveryRepository
    extends KindScopedRepositorySupport<SharedFolderMutationRecovery>
    implements SharedFolderMutationRecoveryRepository {
  public MongoSharedFolderMutationRecoveryRepository(DomainMongoOperationsFactory factory) { super(factory, SharedFolderMutationRecovery.class); }
  @Override public SharedFolderMutationRecovery save(SharedFolderMutationRecovery value) { return saveValue(value); }
  @Override public Optional<SharedFolderMutationRecovery> findById(String id) { return findValueById(id); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public List<SharedFolderMutationRecovery> findTop100ByOwnerIdOrderByUpdatedAtAsc(String ownerId) {
    return find(Query.query(Criteria.where("ownerId").is(ownerId)), PageRequest.of(0, 100, Sort.by("updatedAt")));
  }
  @Override public List<SharedFolderMutationRecovery> findTop100ByOrderByUpdatedAtAsc() {
    return find(new Query(), PageRequest.of(0, 100, Sort.by("updatedAt")));
  }
  @Override public long renewOperationLease(
      String id, String token, SharedFolderMutationRecoveryState state,
      Instant expiresAt, Instant updatedAt) {
    return mongo.updateHeartbeatPreservingVersion(Query.query(Criteria.where("id").is(id)
        .and("operationLeaseToken").is(token).and("state").is(state)),
        new Update().set("operationLeaseExpiresAt", expiresAt).set("updatedAt", updatedAt))
        .getMatchedCount();
  }
  @Override public long claimExpiredOperationLease(
      String id, String expiredToken, SharedFolderMutationRecoveryState state,
      Instant expiredAtOrBefore, String recoveryToken, Instant recoveryExpiresAt,
      Instant updatedAt) {
    var expired = new Criteria().orOperator(
        Criteria.where("operationLeaseExpiresAt").lte(expiredAtOrBefore),
        Criteria.where("operationLeaseExpiresAt").is(null));
    return mongo.updateFirst(Query.query(Criteria.where("id").is(id)
        .and("operationLeaseToken").is(expiredToken).and("state").is(state)
        .andOperator(expired)),
        new Update().set("operationLeaseToken", recoveryToken)
            .set("operationLeaseExpiresAt", recoveryExpiresAt)
            .set("updatedAt", updatedAt))
        .getMatchedCount();
  }
}

package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.configuration.mongo.domain.MongoDatabaseLeaseMutation;
import java.time.Duration;
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
  @Override public Optional<Instant> acquireOperationLease(
      String id, String token, SharedFolderMutationRecoveryState state, Duration duration) {
    return mongo.findAndUpdateDatabaseLease(Query.query(Criteria.where("id").is(id)
            .and("operationLeaseToken").is(null).and("state").is(state)
            .and("operationLeaseExpiresAt").is(null)),
        MongoDatabaseLeaseMutation.claimExpired(
            new Update().set("operationLeaseToken", token).currentDate("updatedAt"),
            "operationLeaseExpiresAt", duration, false))
        .map(SharedFolderMutationRecovery::getOperationLeaseExpiresAt);
  }
  @Override public Optional<Instant> renewOperationLease(
      String id, String token, SharedFolderMutationRecoveryState state, Duration duration) {
    return mongo.findAndUpdateDatabaseLease(Query.query(Criteria.where("id").is(id)
            .and("operationLeaseToken").is(token).and("state").is(state)),
        MongoDatabaseLeaseMutation.renew(new Update().currentDate("updatedAt"),
            "operationLeaseExpiresAt", duration, false))
        .map(SharedFolderMutationRecovery::getOperationLeaseExpiresAt);
  }
  @Override public Optional<Instant> claimExpiredOperationLease(
      String id, String expiredToken, SharedFolderMutationRecoveryState state,
      String recoveryToken, Duration duration) {
    return mongo.findAndUpdateDatabaseLease(Query.query(Criteria.where("id").is(id)
            .and("operationLeaseToken").is(expiredToken).and("state").is(state)),
        MongoDatabaseLeaseMutation.claimExpired(
            new Update().set("operationLeaseToken", recoveryToken).currentDate("updatedAt"),
            "operationLeaseExpiresAt", duration, false))
        .map(SharedFolderMutationRecovery::getOperationLeaseExpiresAt);
  }
}

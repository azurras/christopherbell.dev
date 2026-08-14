package dev.christopherbell.sharedfolder.upload;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import dev.christopherbell.configuration.mongo.domain.MongoDatabaseLeaseMutation;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
public class MongoSharedFolderUploadSessionRepository
    extends KindScopedRepositorySupport<SharedFolderUploadSession>
    implements SharedFolderUploadSessionRepository {
  public MongoSharedFolderUploadSessionRepository(DomainMongoOperationsFactory factory) { super(factory, SharedFolderUploadSession.class); }
  @Override public SharedFolderUploadSession save(SharedFolderUploadSession value) { return saveValue(value); }
  @Override public Optional<SharedFolderUploadSession> findById(String id) { return findValueById(id); }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public long countByOwnerIdAndStateIn(String owner, Collection<SharedFolderUploadState> states) {
    return mongo.count(Query.query(Criteria.where("ownerId").is(owner).and("state").in(states)));
  }
  @Override public Slice<SharedFolderUploadSession> findByOwnerIdOrderByIdAsc(String owner, Pageable page) {
    return slice(Query.query(Criteria.where("ownerId").is(owner)).with(Sort.by("id")), page);
  }
  @Override public Slice<SharedFolderUploadSession> findDueForMaintenance(Instant due, Pageable page) {
    var active = Criteria.where("state").is(SharedFolderUploadState.ACTIVE).and("expiresAt").lte(due);
    var retry = new Criteria().orOperator(Criteria.where("maintenanceRetryAt").lte(due), Criteria.where("maintenanceRetryAt").is(null));
    var expired = Criteria.where("state").is(SharedFolderUploadState.EXPIRED).andOperator(retry);
    return slice(Query.query(new Criteria().orOperator(active, expired)), page);
  }
  @Override public long expireActive(String id, Instant deadline, Instant updatedAt) {
    return update(Query.query(Criteria.where("id").is(id).and("state").is(SharedFolderUploadState.ACTIVE)
        .and("expiresAt").lte(deadline)), new Update().set("state", SharedFolderUploadState.EXPIRED)
        .set("maintenanceRetryAt", updatedAt).set("maintenanceAttempts", 0).set("updatedAt", updatedAt));
  }
  @Override public long deferExpiredMaintenance(String id, int expected, Instant retryAt, int next, Instant updatedAt) {
    var attempts = new Criteria().orOperator(Criteria.where("maintenanceAttempts").is(expected), Criteria.where("maintenanceAttempts").exists(false));
    return update(Query.query(Criteria.where("id").is(id).and("state").is(SharedFolderUploadState.EXPIRED).andOperator(attempts)),
        new Update().set("maintenanceRetryAt", retryAt).set("maintenanceAttempts", next).set("updatedAt", updatedAt));
  }
  @Override public Optional<Instant> renewFinalizationLease(String id, String token,
      SharedFolderUploadFinalizationState state, Duration duration) {
    return mongo.findAndUpdateDatabaseLease(Query.query(Criteria.where("id").is(id)
            .and("state").is(SharedFolderUploadState.FINALIZING)
            .and("finalizationLeaseToken").is(token).and("finalizationState").is(state)),
        MongoDatabaseLeaseMutation.renew(new Update().currentDate("updatedAt"),
            "finalizationLeaseExpiresAt", duration, true))
        .map(SharedFolderUploadSession::getFinalizationLeaseExpiresAt);
  }
  @Override public Optional<Instant> claimExpiredFinalizationLease(String id, String oldToken,
      SharedFolderUploadFinalizationState state, String newToken, Duration duration) {
    return mongo.findAndUpdateDatabaseLease(Query.query(Criteria.where("id").is(id)
            .and("state").is(SharedFolderUploadState.FINALIZING)
            .and("finalizationLeaseToken").is(oldToken).and("finalizationState").is(state)),
        MongoDatabaseLeaseMutation.claimExpired(
            new Update().set("finalizationLeaseToken", newToken).currentDate("updatedAt"),
            "finalizationLeaseExpiresAt", duration, true))
        .map(SharedFolderUploadSession::getFinalizationLeaseExpiresAt);
  }
  @Override public Optional<Instant> renewAppendLease(String id, String token, long offset,
      Duration duration) {
    return mongo.findAndUpdateDatabaseLease(Query.query(Criteria.where("id").is(id)
            .and("state").is(SharedFolderUploadState.APPENDING)
            .and("appendLeaseToken").is(token).and("appendOffset").is(offset)),
        MongoDatabaseLeaseMutation.renew(new Update().currentDate("updatedAt"),
            "appendLeaseExpiresAt", duration, true))
        .map(SharedFolderUploadSession::getAppendLeaseExpiresAt);
  }
  @Override public Optional<Instant> claimExpiredAppendLease(String id, String oldToken, long offset,
      String newToken, Duration duration) {
    return mongo.findAndUpdateDatabaseLease(Query.query(Criteria.where("id").is(id)
            .and("state").is(SharedFolderUploadState.APPENDING)
            .and("appendLeaseToken").is(oldToken).and("appendOffset").is(offset)),
        MongoDatabaseLeaseMutation.claimExpired(
            new Update().set("appendLeaseToken", newToken).currentDate("updatedAt"),
            "appendLeaseExpiresAt", duration, true))
        .map(SharedFolderUploadSession::getAppendLeaseExpiresAt);
  }
  private long update(Query query, Update update) { return mongo.updateFirst(query, update).getMatchedCount(); }
}

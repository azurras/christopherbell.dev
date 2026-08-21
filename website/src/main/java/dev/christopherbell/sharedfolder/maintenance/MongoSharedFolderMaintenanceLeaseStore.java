package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.MongoDatabaseLeaseMutation;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo implementation of the fixed-key atomic maintenance lease boundary. */
@MongoPersistence
@Repository
public class MongoSharedFolderMaintenanceLeaseStore
    implements SharedFolderMaintenanceLeaseStore {
  private final KindScopedMongoOperations<SharedFolderMaintenanceLeaseDocument> mongo;

  @Autowired
  public MongoSharedFolderMaintenanceLeaseStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(SharedFolderMaintenanceLeaseDocument.class);
  }

  MongoSharedFolderMaintenanceLeaseStore(
      KindScopedMongoOperations<SharedFolderMaintenanceLeaseDocument> mongo) {
    this.mongo = mongo;
  }

  @Override
  public Optional<LeaseGrant> tryAcquire(String ownerToken, Duration duration) {
    new LeaseIdentity(SharedFolderMaintenanceLeaseDocument.ID, ownerToken);
    Query query = Query.query(Criteria.where("id").is(SharedFolderMaintenanceLeaseDocument.ID));
    Update update = new Update()
        .set("ownerToken", ownerToken)
        .inc("fenceToken", 1L)
        .currentDate("acquiredAt");
    var seed = new SharedFolderMaintenanceLeaseDocument();
    seed.setId(SharedFolderMaintenanceLeaseDocument.ID);
    seed.setOwnerToken("unclaimed");
    seed.setFenceToken(0L);
    seed.setAcquiredAt(Instant.EPOCH);
    seed.setExpiresAt(Instant.EPOCH);
    return mongo.acquireDatabaseLease(query, MongoDatabaseLeaseMutation.acquire(
            update, "expiresAt", duration, "ownerToken", ownerToken), seed)
        .map(MongoSharedFolderMaintenanceLeaseStore::grant);
  }

  @Override
  public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
    if (!SharedFolderMaintenanceLeaseDocument.ID.equals(grant.leaseName())) {
      return Optional.empty();
    }
    Query query = Query.query(Criteria.where("id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .and("ownerToken").is(grant.ownerId())
        .and("fenceToken").is(grant.fenceToken()));
    return mongo.findAndUpdateDatabaseLease(query, MongoDatabaseLeaseMutation.renew(
            new Update().set("ownerToken", grant.ownerId()), "expiresAt", duration, false))
        .map(MongoSharedFolderMaintenanceLeaseStore::grant);
  }

  @Override
  public boolean release(LeaseGrant grant) {
    if (!SharedFolderMaintenanceLeaseDocument.ID.equals(grant.leaseName())) {
      return false;
    }
    Query query = Query.query(Criteria.where("id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .and("ownerToken").is(grant.ownerId())
        .and("fenceToken").is(grant.fenceToken()));
    Update update = new Update()
        .set("ownerToken", "released")
        .set("expiresAt", Instant.EPOCH);
    return mongo.findAndUpdateDatabaseLease(query,
        MongoDatabaseLeaseMutation.release(update, "expiresAt", false)).isPresent();
  }

  private static LeaseGrant grant(SharedFolderMaintenanceLeaseDocument document) {
    return new LeaseGrant(document.getId(), document.getOwnerToken(), document.getFenceToken(),
        document.getExpiresAt());
  }
}

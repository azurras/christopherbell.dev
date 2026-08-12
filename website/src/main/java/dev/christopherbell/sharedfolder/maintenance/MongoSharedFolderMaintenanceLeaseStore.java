package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo implementation of the fixed-key atomic maintenance lease boundary. */
@Repository
class MongoSharedFolderMaintenanceLeaseStore implements SharedFolderMaintenanceLeaseStore {
  private final KindScopedMongoOperations<SharedFolderMaintenanceLeaseDocument> mongo;

  @Autowired
  MongoSharedFolderMaintenanceLeaseStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(SharedFolderMaintenanceLeaseDocument.class);
  }

  MongoSharedFolderMaintenanceLeaseStore(
      KindScopedMongoOperations<SharedFolderMaintenanceLeaseDocument> mongo) {
    this.mongo = mongo;
  }

  @Override
  public boolean tryAcquire(String ownerToken, Instant acquiredAt, Instant expiresAt) {
    Query query = Query.query(Criteria.where("id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .orOperator(
            Criteria.where("ownerToken").is(ownerToken),
            Criteria.where("expiresAt").lte(acquiredAt)));
    Update update = new Update()
        .set("ownerToken", ownerToken)
        .set("acquiredAt", acquiredAt)
        .set("expiresAt", expiresAt);
    if (mongo.findAndUpdate(query, update).isPresent()) {
      return true;
    }
    var lease = new SharedFolderMaintenanceLeaseDocument();
    lease.setId(SharedFolderMaintenanceLeaseDocument.ID);
    lease.setOwnerToken(ownerToken);
    lease.setAcquiredAt(acquiredAt);
    lease.setExpiresAt(expiresAt);
    try {
      mongo.insert(lease);
      return true;
    } catch (DuplicateKeyException contention) {
      return false;
    }
  }

  @Override
  public boolean renew(String ownerToken, Instant renewedAt, Instant expiresAt) {
    Query query = Query.query(Criteria.where("id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .and("ownerToken").is(ownerToken)
        .and("expiresAt").gt(renewedAt));
    Update update = new Update().set("expiresAt", expiresAt);
    return mongo.updateFirst(query, update)
        .getMatchedCount() == 1;
  }

  @Override
  public boolean release(String ownerToken) {
    Query query = Query.query(Criteria.where("id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .and("ownerToken").is(ownerToken));
    Update update = new Update()
        .unset("ownerToken")
        .set("expiresAt", Instant.EPOCH);
    return mongo.updateFirst(query, update)
        .getMatchedCount() == 1;
  }
}

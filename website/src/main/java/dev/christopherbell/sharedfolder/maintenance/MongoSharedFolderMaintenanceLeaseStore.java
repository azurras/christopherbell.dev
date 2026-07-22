package dev.christopherbell.sharedfolder.maintenance;

import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Mongo implementation of the fixed-key atomic maintenance lease boundary. */
@Repository
class MongoSharedFolderMaintenanceLeaseStore implements SharedFolderMaintenanceLeaseStore {
  private final MongoTemplate mongo;

  MongoSharedFolderMaintenanceLeaseStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public boolean tryAcquire(String ownerToken, Instant acquiredAt, Instant expiresAt) {
    Query query = Query.query(Criteria.where("_id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .orOperator(
            Criteria.where("ownerToken").is(ownerToken),
            Criteria.where("expiresAt").lte(acquiredAt)));
    Update update = new Update()
        .set("ownerToken", ownerToken)
        .set("acquiredAt", acquiredAt)
        .set("expiresAt", expiresAt);
    try {
      SharedFolderMaintenanceLeaseDocument acquired = mongo.findAndModify(
          query, update, FindAndModifyOptions.options().upsert(true).returnNew(true),
          SharedFolderMaintenanceLeaseDocument.class);
      return acquired != null && ownerToken.equals(acquired.getOwnerToken());
    } catch (DuplicateKeyException contention) {
      return false;
    }
  }

  @Override
  public boolean renew(String ownerToken, Instant renewedAt, Instant expiresAt) {
    Query query = Query.query(Criteria.where("_id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .and("ownerToken").is(ownerToken)
        .and("expiresAt").gt(renewedAt));
    Update update = new Update().set("expiresAt", expiresAt);
    return mongo.updateFirst(query, update, SharedFolderMaintenanceLeaseDocument.class)
        .getMatchedCount() == 1;
  }

  @Override
  public boolean release(String ownerToken) {
    Query query = Query.query(Criteria.where("_id")
        .is(SharedFolderMaintenanceLeaseDocument.ID)
        .and("ownerToken").is(ownerToken));
    Update update = new Update()
        .unset("ownerToken")
        .set("expiresAt", Instant.EPOCH);
    return mongo.updateFirst(query, update, SharedFolderMaintenanceLeaseDocument.class)
        .getMatchedCount() == 1;
  }
}

package dev.christopherbell.configuration.mongo.lease;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Provides atomic, owner-scoped leases stored in MongoDB. */
@Service
@RequiredArgsConstructor
public class MongoLeaseService {
  public static final String COLLECTION = "application_leases";

  private final MongoTemplate mongo;

  public boolean tryAcquire(
      String name, String ownerToken, Instant now, Instant expiresAt) {
    var ownerOrExpired = new Criteria().orOperator(
        Criteria.where("ownerToken").is(ownerToken),
        Criteria.where("expiresAt").lte(now));
    var query = Query.query(Criteria.where("_id").is(name).andOperator(ownerOrExpired));
    var update = new Update()
        .set("ownerToken", ownerToken)
        .set("acquiredAt", now)
        .set("expiresAt", expiresAt);
    var options = FindAndModifyOptions.options().upsert(true).returnNew(true);
    try {
      var lease = mongo.findAndModify(query, update, options, MongoLeaseDocument.class);
      return lease != null && ownerToken.equals(lease.getOwnerToken());
    } catch (DuplicateKeyException contention) {
      return false;
    }
  }

  public boolean renew(String name, String ownerToken, Instant now, Instant expiresAt) {
    var query = Query.query(Criteria.where("_id").is(name)
        .and("ownerToken").is(ownerToken)
        .and("expiresAt").gt(now));
    var result = mongo.updateFirst(
        query, new Update().set("expiresAt", expiresAt), MongoLeaseDocument.class);
    return result.getModifiedCount() == 1;
  }

  public boolean release(String name, String ownerToken) {
    var query = Query.query(Criteria.where("_id").is(name).and("ownerToken").is(ownerToken));
    var update = new Update()
        .unset("ownerToken")
        .set("expiresAt", Instant.EPOCH);
    return mongo.updateFirst(query, update, MongoLeaseDocument.class).getModifiedCount() == 1;
  }
}

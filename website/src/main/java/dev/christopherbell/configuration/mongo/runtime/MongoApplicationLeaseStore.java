package dev.christopherbell.configuration.mongo.runtime;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.libs.mongo.lease.MongoLeaseDocument;
import dev.christopherbell.libs.mongo.lease.MongoLeaseStore;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Kind-scoped application lease adapter with one atomic owner transition. */
@MongoPersistence
@Repository
public class MongoApplicationLeaseStore implements MongoLeaseStore {
  private final KindScopedMongoOperations<MongoLeaseDocument> mongo;

  public MongoApplicationLeaseStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(MongoLeaseDocument.class);
  }

  @Override
  public boolean tryAcquire(String name, String ownerToken, Instant now, Instant expiresAt) {
    var ownerOrExpired = new Criteria().orOperator(
        Criteria.where("ownerToken").is(ownerToken),
        Criteria.where("expiresAt").lte(now));
    var query = Query.query(Criteria.where("id").is(name).andOperator(ownerOrExpired));
    var update = new Update()
        .set("ownerToken", ownerToken)
        .set("acquiredAt", now)
        .set("expiresAt", expiresAt);
    if (mongo.findAndUpdate(query, update).isPresent()) {
      return true;
    }
    var lease = new MongoLeaseDocument();
    lease.setId(name);
    lease.setOwnerToken(ownerToken);
    lease.setAcquiredAt(now);
    lease.setExpiresAt(expiresAt);
    try {
      mongo.insert(lease);
      return true;
    } catch (DuplicateKeyException contention) {
      return false;
    }
  }

  @Override
  public boolean renew(String name, String ownerToken, Instant now, Instant expiresAt) {
    var query = Query.query(Criteria.where("id").is(name)
        .and("ownerToken").is(ownerToken)
        .and("expiresAt").gt(now));
    return mongo.updateFirst(query, new Update().set("expiresAt", expiresAt))
        .getMatchedCount() == 1;
  }

  @Override
  public boolean release(String name, String ownerToken) {
    var query = Query.query(Criteria.where("id").is(name).and("ownerToken").is(ownerToken));
    return mongo.updateFirst(
        query, new Update().unset("ownerToken").set("expiresAt", Instant.EPOCH))
        .getMatchedCount() == 1;
  }
}

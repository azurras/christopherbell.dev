package dev.christopherbell.configuration.mongo.runtime;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.MongoDatabaseLeaseMutation;
import dev.christopherbell.libs.mongo.lease.MongoLeaseDocument;
import dev.christopherbell.libs.mongo.lease.MongoLeaseStore;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseIdentity;
import dev.christopherbell.libs.lease.LeaseStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Kind-scoped application lease adapter with one atomic owner transition. */
@MongoPersistence
@Repository
public class MongoApplicationLeaseStore implements MongoLeaseStore, LeaseStore {
  private final KindScopedMongoOperations<MongoLeaseDocument> mongo;

  public MongoApplicationLeaseStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(MongoLeaseDocument.class);
  }

  @Override
  public boolean tryAcquire(String name, String ownerToken, Instant now, Instant expiresAt) {
    new LeaseIdentity(name, ownerToken);
    var ownerOrExpired = new Criteria().orOperator(
        Criteria.where("ownerToken").is(ownerToken),
        Criteria.where("expiresAt").lte(now));
    var query = Query.query(Criteria.where("id").is(name).andOperator(ownerOrExpired));
    var update = new Update()
        .set("ownerToken", ownerToken)
        .inc("fenceToken", 1)
        .set("acquiredAt", now)
        .set("expiresAt", expiresAt);
    if (mongo.findAndUpdate(query, update).isPresent()) {
      return true;
    }
    var lease = new MongoLeaseDocument();
    lease.setId(name);
    lease.setOwnerToken(ownerToken);
    lease.setFenceToken(1L);
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
    new LeaseIdentity(name, ownerToken);
    var query = Query.query(Criteria.where("id").is(name)
        .and("ownerToken").is(ownerToken)
        .and("expiresAt").gt(now));
    return mongo.updateFirst(query, new Update().set("expiresAt", expiresAt))
        .getMatchedCount() == 1;
  }

  @Override
  public boolean release(String name, String ownerToken) {
    new LeaseIdentity(name, ownerToken);
    var query = Query.query(Criteria.where("id").is(name).and("ownerToken").is(ownerToken));
    return mongo.updateFirst(
        query, new Update().unset("ownerToken").set("expiresAt", Instant.EPOCH))
        .getMatchedCount() == 1;
  }

  @Override
  public Optional<LeaseGrant> tryAcquire(String name, String ownerToken, Duration duration) {
    new LeaseIdentity(name, ownerToken);
    var query = Query.query(Criteria.where("id").is(name));
    var update = new Update()
        .set("ownerToken", ownerToken)
        .inc("fenceToken", 1L)
        .currentDate("acquiredAt");
    var seed = new MongoLeaseDocument();
    seed.setId(name);
    seed.setOwnerToken("unclaimed");
    seed.setFenceToken(0L);
    seed.setAcquiredAt(Instant.EPOCH);
    seed.setExpiresAt(Instant.EPOCH);
    return mongo.acquireDatabaseLease(query, MongoDatabaseLeaseMutation.acquire(
            update, "expiresAt", duration, "ownerToken", ownerToken), seed)
        .map(MongoApplicationLeaseStore::grant);
  }

  @Override
  public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
    var query = Query.query(Criteria.where("id").is(grant.leaseName())
        .and("ownerToken").is(grant.ownerId()).and("fenceToken").is(grant.fenceToken()));
    return mongo.findAndUpdateDatabaseLease(query, MongoDatabaseLeaseMutation.renew(
            new Update().set("ownerToken", grant.ownerId()), "expiresAt", duration, false))
        .map(MongoApplicationLeaseStore::grant);
  }

  @Override
  public boolean release(LeaseGrant grant) {
    var query = Query.query(Criteria.where("id").is(grant.leaseName())
        .and("ownerToken").is(grant.ownerId()).and("fenceToken").is(grant.fenceToken()));
    return mongo.updateFirst(query,
        new Update().set("ownerToken", "released").set("expiresAt", Instant.EPOCH))
        .getMatchedCount() == 1;
  }

  private static LeaseGrant grant(MongoLeaseDocument value) {
    return new LeaseGrant(value.getId(), value.getOwnerToken(), value.getFenceToken(),
        value.getExpiresAt());
  }

}

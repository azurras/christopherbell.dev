package dev.christopherbell.configuration.mongo.runtime;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.MongoDatabaseLeaseMutation;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseIdentity;
import dev.christopherbell.libs.lease.LeaseStore;
import dev.christopherbell.libs.mongo.lease.MongoLeaseDocument;
import dev.christopherbell.libs.mongo.lease.MongoLeaseStore;
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
  /** Owner of a lease document seeded before its first acquisition. */
  private static final String UNCLAIMED_OWNER = "unclaimed";
  /** Owner left behind when a fenced grant is released. */
  private static final String RELEASED_OWNER = "released";

  private final KindScopedMongoOperations<MongoLeaseDocument> mongo;

  public MongoApplicationLeaseStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(MongoLeaseDocument.class);
  }

  @Override
  public boolean tryAcquire(String name, String ownerToken, Instant now, Instant expiresAt) {
    requireValidIdentity(name, ownerToken);
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
    requireValidIdentity(name, ownerToken);
    var query = Query.query(Criteria.where("id").is(name)
        .and("ownerToken").is(ownerToken)
        .and("expiresAt").gt(now));
    return mongo.updateFirst(query, new Update().set("expiresAt", expiresAt))
        .getMatchedCount() == 1;
  }

  @Override
  public boolean release(String name, String ownerToken) {
    requireValidIdentity(name, ownerToken);
    var query = Query.query(Criteria.where("id").is(name).and("ownerToken").is(ownerToken));
    return mongo.updateFirst(
        query, new Update().unset("ownerToken").set("expiresAt", Instant.EPOCH))
        .getMatchedCount() == 1;
  }

  @Override
  public Optional<LeaseGrant> tryAcquire(String name, String ownerToken, Duration duration) {
    requireValidIdentity(name, ownerToken);
    var query = Query.query(Criteria.where("id").is(name));
    var update = new Update()
        .set("ownerToken", ownerToken)
        .inc("fenceToken", 1L)
        .currentDate("acquiredAt");
    var seed = new MongoLeaseDocument();
    seed.setId(name);
    seed.setOwnerToken(UNCLAIMED_OWNER);
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
        new Update().set("ownerToken", RELEASED_OWNER).set("expiresAt", Instant.EPOCH))
        .getMatchedCount() == 1;
  }

  /** Rejects a blank or over-long lease name or owner token, through {@link LeaseIdentity}'s rules. */
  private static void requireValidIdentity(String name, String ownerToken) {
    new LeaseIdentity(name, ownerToken);
  }

  private static LeaseGrant grant(MongoLeaseDocument value) {
    return new LeaseGrant(value.getId(), value.getOwnerToken(), value.getFenceToken(),
        value.getExpiresAt());
  }
}

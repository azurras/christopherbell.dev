package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Mongo-backed atomic activity transitions for browser sessions.
 *
 * <p>Intentionally non-final so Spring can apply class-based persistence exception translation.
 */
@Repository
public class MongoBrowserSessionActivityStore implements BrowserSessionActivityStore {
  private final KindScopedMongoOperations<BrowserSession> mongo;

  public MongoBrowserSessionActivityStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(BrowserSession.class);
  }

  @Override
  public Optional<BrowserSession> touch(
      String sessionId, Instant observedLastSeenOn, Instant now, Instant idleExpiresOn) {
    var update = new Update()
        .set("lastSeenOn", now)
        .set("idleExpiresOn", idleExpiresOn);
    return findAndModify(touchQuery(sessionId, observedLastSeenOn, now, idleExpiresOn), update);
  }

  @Override
  public Optional<BrowserSession> rotate(
      String sessionId,
      String observedTokenHash,
      Instant observedRotatedOn,
      String nextTokenHash,
      Instant now,
      Instant previousTokenExpiresOn,
      Instant idleExpiresOn) {
    var update = new Update()
        .set("previousTokenHash", observedTokenHash)
        .set("previousTokenExpiresOn", previousTokenExpiresOn)
        .set("tokenHash", nextTokenHash)
        .set("rotatedOn", now)
        .set("lastSeenOn", now)
        .set("idleExpiresOn", idleExpiresOn);
    return findAndModify(
        rotationQuery(sessionId, observedTokenHash, observedRotatedOn, now, idleExpiresOn), update);
  }

  private Optional<BrowserSession> findAndModify(Query query, Update update) {
    return mongo.findAndUpdate(query, update);
  }

  static Query touchQuery(
      String sessionId, Instant observedLastSeenOn, Instant now, Instant idleExpiresOn) {
    return liveSessionQuery(sessionId, now, idleExpiresOn)
        .addCriteria(Criteria.where("lastSeenOn").is(observedLastSeenOn));
  }

  static Query rotationQuery(
      String sessionId,
      String observedTokenHash,
      Instant observedRotatedOn,
      Instant now,
      Instant idleExpiresOn) {
    return liveSessionQuery(sessionId, now, idleExpiresOn)
        .addCriteria(Criteria.where("tokenHash").is(observedTokenHash))
        .addCriteria(Criteria.where("rotatedOn").is(observedRotatedOn));
  }

  private static Query liveSessionQuery(String sessionId, Instant now, Instant idleExpiresOn) {
    return new Query(new Criteria().andOperator(
        Criteria.where("id").is(sessionId),
        Criteria.where("idleExpiresOn").gt(now),
        Criteria.where("absoluteExpiresOn").gte(idleExpiresOn)));
  }
}

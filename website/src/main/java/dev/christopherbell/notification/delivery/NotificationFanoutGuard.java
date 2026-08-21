package dev.christopherbell.notification.delivery;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Atomically claims dedupe and actor-recipient rate-limit permits before fanout. */
@MongoPersistence
public class NotificationFanoutGuard implements NotificationFanoutPort {
  private final KindScopedMongoOperations<NotificationDeliveryGuard> claims;
  private final KindScopedMongoOperations<NotificationRateLimit> rates;
  private final NotificationDeliveryProperties properties;

  public NotificationFanoutGuard(
      DomainMongoOperationsFactory factory, NotificationDeliveryProperties properties) {
    this.claims = factory.forType(NotificationDeliveryGuard.class);
    this.rates = factory.forType(NotificationRateLimit.class);
    this.properties = properties;
  }

  /** Returns a permit exactly once per event while its actor rate remains available. */
  public Optional<NotificationDeliveryPermit> tryAcquire(
      NotificationEventIdentity identity,
      Instant now
  ) {
    String claimId = hash(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), identity.targetId()));
    var claimQuery = new Query(new Criteria().andOperator(
        Criteria.where("id").is(claimId),
        new Criteria().orOperator(
            Criteria.where("expiresAt").lte(now),
            Criteria.where("expiresAt").exists(false))));
    var claimUpdate = new Update()
        .set("accountId", identity.recipientAccountId())
        .set("actorAccountId", identity.actorAccountId())
        .set("notificationType", identity.type().name())
        .set("targetId", identity.targetId())
        .set("expiresAt", now.plus(properties.dedupeWindow()));
    try {
      claims.insert(NotificationDeliveryGuard.builder().id(claimId)
          .accountId(identity.recipientAccountId()).actorAccountId(identity.actorAccountId())
          .notificationType(identity.type().name()).targetId(identity.targetId())
          .expiresAt(now.plus(properties.dedupeWindow())).build());
    } catch (DuplicateKeyException duplicate) {
      if (claims.findAndUpdate(claimQuery, claimUpdate).isEmpty()) return Optional.empty();
    }

    long windowMillis = properties.rateWindow().toMillis();
    long bucket = Math.floorDiv(now.toEpochMilli(), windowMillis);
    String rateId = hash(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), Long.toString(bucket)));
    var query = Query.query(Criteria.where("id").is(rateId));
    var update = new Update().inc("count", 1L);
    var counter = rates.findAndUpdate(query, update).orElseGet(() -> insertRateCounter(
        rateId, identity, now));
    if (counter.getCount() > properties.maxEventsPerWindow()) {
      release(new NotificationDeliveryPermit(claimId, rateId));
      return Optional.empty();
    }
    return Optional.of(new NotificationDeliveryPermit(claimId, rateId));
  }

  /** Releases a dedupe claim when downstream notification persistence did not commit. */
  public void release(NotificationDeliveryPermit permit) {
    if (permit == null) return;
    var reservedRate = new Query(new Criteria().andOperator(
        Criteria.where("id").is(permit.rateId()),
        Criteria.where("count").gt(0L)));
    rates.updateFirst(reservedRate, new Update().inc("count", -1L));
    releaseClaim(permit.claimId());
  }

  @Override
  public NotificationCleanupResult deleteExpired(Instant cutoff, int batchLimit) {
    requireBatchLimit(batchLimit);
    var guardIds = claims.find(expiredQuery(cutoff, batchLimit),
            org.springframework.data.domain.Pageable.unpaged())
        .stream().map(NotificationDeliveryGuard::getId).toList();
    var rateIds = rates.find(expiredQuery(cutoff, batchLimit),
            org.springframework.data.domain.Pageable.unpaged())
        .stream().map(NotificationRateLimit::getId).toList();
    var guardsDeleted = guardIds.isEmpty() ? 0 : Math.toIntExact(claims.remove(
        Query.query(Criteria.where("id").in(guardIds))).getDeletedCount());
    var ratesDeleted = rateIds.isEmpty() ? 0 : Math.toIntExact(rates.remove(
        Query.query(Criteria.where("id").in(rateIds))).getDeletedCount());
    return new NotificationCleanupResult(guardsDeleted, ratesDeleted);
  }

  private void releaseClaim(String claimId) {
    claims.remove(Query.query(Criteria.where("id").is(claimId)));
  }

  private NotificationRateLimit insertRateCounter(
      String rateId, NotificationEventIdentity identity, Instant now) {
    var value = NotificationRateLimit.builder().id(rateId)
        .accountId(identity.recipientAccountId()).actorAccountId(identity.actorAccountId())
        .notificationType(identity.type().name()).count(1L)
        .expiresAt(now.plus(properties.rateWindow())).build();
    try {
      return rates.insert(value);
    } catch (DuplicateKeyException duplicate) {
      return rates.findAndUpdate(
          Query.query(Criteria.where("id").is(rateId)), new Update().inc("count", 1L))
          .orElseThrow(() -> new IllegalStateException(
              "Notification rate counter was not returned."));
    }
  }

  private String hash(String value) {
    try {
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  private static Query expiredQuery(Instant cutoff, int batchLimit) {
    return Query.query(Criteria.where("expiresAt").lte(cutoff))
        .with(Sort.by("expiresAt").ascending())
        .limit(batchLimit);
  }

  private static void requireBatchLimit(int batchLimit) {
    if (batchLimit < 1) throw new IllegalArgumentException("Cleanup batch limit must be positive");
  }
}

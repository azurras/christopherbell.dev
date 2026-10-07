package dev.christopherbell.notification.delivery;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
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

  /**
   * Returns a permit at most once per event within the dedupe window, and only while the
   * actor's rate toward this recipient and type stays within its window limit.
   *
   * <p>A claim is new when it is inserted, or when an expired claim with the same id is
   * refreshed. A permit that exceeds the rate limit is released before returning empty.</p>
   */
  @Override
  public Optional<NotificationDeliveryPermit> tryAcquire(NotificationEventIdentity identity, Instant now) {
    String claimId = sha256Hex(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), identity.targetId()));
    if (!claimEvent(claimId, identity, now)) {
      return Optional.empty();
    }

    long rateWindowIndex = Math.floorDiv(now.toEpochMilli(), properties.rateWindow().toMillis());
    String rateId = sha256Hex(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), Long.toString(rateWindowIndex)));
    NotificationRateLimit rateCounter = rates.findAndUpdate(byId(rateId), new Update().inc("count", 1L))
        .orElseGet(() -> insertRateCounter(rateId, identity, now));
    NotificationDeliveryPermit permit = new NotificationDeliveryPermit(claimId, rateId);
    if (rateCounter.getCount() > properties.maxEventsPerWindow()) {
      release(permit);
      return Optional.empty();
    }
    return Optional.of(permit);
  }

  /** Releases a permit when downstream notification persistence did not commit. */
  @Override
  public void release(NotificationDeliveryPermit permit) {
    if (permit == null) {
      return;
    }
    Query reservedRate = new Query(new Criteria().andOperator(
        Criteria.where("id").is(permit.rateId()),
        Criteria.where("count").gt(0L)));
    rates.updateFirst(reservedRate, new Update().inc("count", -1L));
    claims.remove(byId(permit.claimId()));
  }

  @Override
  public NotificationCleanupResult deleteExpired(Instant cutoff, int batchLimit) {
    if (batchLimit < 1) {
      throw new IllegalArgumentException("Cleanup batch limit must be positive");
    }
    List<String> expiredClaimIds = claims.find(expiredQuery(cutoff, batchLimit), Pageable.unpaged())
        .stream()
        .map(NotificationDeliveryGuard::getId)
        .toList();
    List<String> expiredRateIds = rates.find(expiredQuery(cutoff, batchLimit), Pageable.unpaged())
        .stream()
        .map(NotificationRateLimit::getId)
        .toList();
    int claimsDeleted = expiredClaimIds.isEmpty()
        ? 0
        : Math.toIntExact(claims.remove(Query.query(Criteria.where("id").in(expiredClaimIds)))
            .getDeletedCount());
    int ratesDeleted = expiredRateIds.isEmpty()
        ? 0
        : Math.toIntExact(rates.remove(Query.query(Criteria.where("id").in(expiredRateIds)))
            .getDeletedCount());
    return new NotificationCleanupResult(claimsDeleted, ratesDeleted);
  }

  /** Inserts a new claim, or takes over an expired one; false when a live claim exists. */
  private boolean claimEvent(String claimId, NotificationEventIdentity identity, Instant now) {
    Instant claimExpiresAt = now.plus(properties.dedupeWindow());
    try {
      claims.insert(NotificationDeliveryGuard.builder()
          .id(claimId)
          .accountId(identity.recipientAccountId())
          .actorAccountId(identity.actorAccountId())
          .notificationType(identity.type().name())
          .targetId(identity.targetId())
          .expiresAt(claimExpiresAt)
          .build());
      return true;
    } catch (DuplicateKeyException existingClaim) {
      Query expiredClaim = new Query(new Criteria().andOperator(
          Criteria.where("id").is(claimId),
          new Criteria().orOperator(
              Criteria.where("expiresAt").lte(now),
              Criteria.where("expiresAt").exists(false))));
      Update renewedClaim = new Update()
          .set("accountId", identity.recipientAccountId())
          .set("actorAccountId", identity.actorAccountId())
          .set("notificationType", identity.type().name())
          .set("targetId", identity.targetId())
          .set("expiresAt", claimExpiresAt);
      return claims.findAndUpdate(expiredClaim, renewedClaim).isPresent();
    }
  }

  private NotificationRateLimit insertRateCounter(
      String rateId, NotificationEventIdentity identity, Instant now) {
    NotificationRateLimit firstEventInWindow = NotificationRateLimit.builder()
        .id(rateId)
        .accountId(identity.recipientAccountId())
        .actorAccountId(identity.actorAccountId())
        .notificationType(identity.type().name())
        .count(1L)
        .expiresAt(now.plus(properties.rateWindow()))
        .build();
    try {
      return rates.insert(firstEventInWindow);
    } catch (DuplicateKeyException concurrentFirstEvent) {
      return rates.findAndUpdate(byId(rateId), new Update().inc("count", 1L))
          .orElseThrow(() -> new IllegalStateException("Notification rate counter was not returned."));
    }
  }

  private static Query byId(String documentId) {
    return Query.query(Criteria.where("id").is(documentId));
  }

  private static String sha256Hex(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
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
}

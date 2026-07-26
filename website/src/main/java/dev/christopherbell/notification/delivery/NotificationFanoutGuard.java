package dev.christopherbell.notification.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Atomically claims dedupe and actor-recipient rate-limit permits before fanout. */
@Service
@RequiredArgsConstructor
public class NotificationFanoutGuard {
  private final MongoTemplate mongo;
  private final NotificationDeliveryProperties properties;

  /** Returns a permit exactly once per event while its actor rate remains available. */
  public Optional<NotificationDeliveryPermit> tryAcquire(
      NotificationEventIdentity identity,
      Instant now
  ) {
    String claimId = hash(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), identity.targetId()));
    var claim = NotificationDeliveryGuard.builder()
        .id(claimId)
        .accountId(identity.recipientAccountId())
        .actorAccountId(identity.actorAccountId())
        .notificationType(identity.type().name())
        .targetId(identity.targetId())
        .expiresAt(now.plus(properties.dedupeWindow()))
        .build();
    try {
      mongo.insert(claim);
    } catch (DuplicateKeyException duplicate) {
      return Optional.empty();
    }

    long windowMillis = properties.rateWindow().toMillis();
    long bucket = Math.floorDiv(now.toEpochMilli(), windowMillis);
    String rateId = hash(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), Long.toString(bucket)));
    var query = Query.query(Criteria.where("_id").is(rateId));
    var update = new Update()
        .setOnInsert("accountId", identity.recipientAccountId())
        .setOnInsert("actorAccountId", identity.actorAccountId())
        .setOnInsert("notificationType", identity.type().name())
        .setOnInsert("expiresAt", now.plus(properties.rateWindow()))
        .inc("count", 1L);
    var counter = mongo.findAndModify(
        query,
        update,
        FindAndModifyOptions.options().upsert(true).returnNew(true),
        NotificationRateLimit.class);
    if (counter == null) {
      release(claimId);
      throw new IllegalStateException("Notification rate counter was not returned.");
    }
    if (counter.getCount() > properties.maxEventsPerWindow()) {
      release(claimId);
      return Optional.empty();
    }
    return Optional.of(new NotificationDeliveryPermit(claimId));
  }

  private void release(String claimId) {
    mongo.remove(Query.query(Criteria.where("_id").is(claimId)), NotificationDeliveryGuard.class);
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
}

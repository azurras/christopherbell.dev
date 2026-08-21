package dev.christopherbell.notification.delivery;

import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION_DELIVERY_GUARD;
import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION_RATE_LIMIT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL atomic notification dedupe and fixed-window rate-limit implementation. */
@PostgresPersistence
public class PostgresNotificationFanoutGuard implements NotificationFanoutPort {
  private final DSLContext database;
  private final NotificationDeliveryProperties properties;

  public PostgresNotificationFanoutGuard(
      DSLContext database, NotificationDeliveryProperties properties) {
    this.database = database;
    this.properties = properties;
  }

  @Override
  public Optional<NotificationDeliveryPermit> tryAcquire(
      NotificationEventIdentity identity, Instant now) {
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var claimId = hash(String.join("\n",
          identity.recipientAccountId(), identity.actorAccountId(),
          identity.type().name(), identity.targetId()));
      var expiresAt = now.plus(properties.dedupeWindow()).atOffset(ZoneOffset.UTC);
      var claimed = transaction.insertInto(NOTIFICATION_DELIVERY_GUARD)
          .set(NOTIFICATION_DELIVERY_GUARD.GUARD_ID, claimId)
          .set(NOTIFICATION_DELIVERY_GUARD.ACCOUNT_ID, identity.recipientAccountId())
          .set(NOTIFICATION_DELIVERY_GUARD.ACTOR_ACCOUNT_ID, identity.actorAccountId())
          .set(NOTIFICATION_DELIVERY_GUARD.NOTIFICATION_TYPE, identity.type().name())
          .set(NOTIFICATION_DELIVERY_GUARD.TARGET_ID, identity.targetId())
          .set(NOTIFICATION_DELIVERY_GUARD.EXPIRES_AT, expiresAt)
          .onConflict(NOTIFICATION_DELIVERY_GUARD.GUARD_ID)
          .doUpdate()
          .set(NOTIFICATION_DELIVERY_GUARD.ACCOUNT_ID, identity.recipientAccountId())
          .set(NOTIFICATION_DELIVERY_GUARD.ACTOR_ACCOUNT_ID, identity.actorAccountId())
          .set(NOTIFICATION_DELIVERY_GUARD.NOTIFICATION_TYPE, identity.type().name())
          .set(NOTIFICATION_DELIVERY_GUARD.TARGET_ID, identity.targetId())
          .set(NOTIFICATION_DELIVERY_GUARD.EXPIRES_AT, expiresAt)
          .set(NOTIFICATION_DELIVERY_GUARD.VERSION,
              NOTIFICATION_DELIVERY_GUARD.VERSION.plus(1L))
          .where(NOTIFICATION_DELIVERY_GUARD.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC)))
          .returning(NOTIFICATION_DELIVERY_GUARD.GUARD_ID)
          .fetchOptional()
          .isPresent();
      if (!claimed) return Optional.empty();

      var bucket = Math.floorDiv(now.toEpochMilli(), properties.rateWindow().toMillis());
      var rateId = hash(String.join("\n",
          identity.recipientAccountId(), identity.actorAccountId(),
          identity.type().name(), Long.toString(bucket)));
      var count = transaction.insertInto(NOTIFICATION_RATE_LIMIT)
          .set(NOTIFICATION_RATE_LIMIT.RATE_LIMIT_ID, rateId)
          .set(NOTIFICATION_RATE_LIMIT.ACCOUNT_ID, identity.recipientAccountId())
          .set(NOTIFICATION_RATE_LIMIT.ACTOR_ACCOUNT_ID, identity.actorAccountId())
          .set(NOTIFICATION_RATE_LIMIT.NOTIFICATION_TYPE, identity.type().name())
          .set(NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT, 1L)
          .set(NOTIFICATION_RATE_LIMIT.EXPIRES_AT,
              now.plus(properties.rateWindow()).atOffset(ZoneOffset.UTC))
          .onConflict(NOTIFICATION_RATE_LIMIT.RATE_LIMIT_ID)
          .doUpdate()
          .set(NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT,
              NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT.plus(1L))
          .set(NOTIFICATION_RATE_LIMIT.VERSION, NOTIFICATION_RATE_LIMIT.VERSION.plus(1L))
          .returning(NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT)
          .fetchOne(NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT);
      var permit = new NotificationDeliveryPermit(claimId, rateId);
      if (count != null && count > properties.maxEventsPerWindow()) {
        release(transaction, permit);
        return Optional.empty();
      }
      return Optional.of(permit);
    });
  }

  @Override
  public void release(NotificationDeliveryPermit permit) {
    if (permit == null) return;
    database.transaction(configuration -> release(DSL.using(configuration), permit));
  }

  @Override
  public NotificationCleanupResult deleteExpired(Instant cutoff, int batchLimit) {
    if (batchLimit < 1) throw new IllegalArgumentException("Cleanup batch limit must be positive");
    var timestamp = cutoff.atOffset(ZoneOffset.UTC);
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var guards = transaction.select(NOTIFICATION_DELIVERY_GUARD.GUARD_ID)
          .from(NOTIFICATION_DELIVERY_GUARD)
          .where(NOTIFICATION_DELIVERY_GUARD.EXPIRES_AT.le(timestamp))
          .orderBy(NOTIFICATION_DELIVERY_GUARD.EXPIRES_AT.asc(),
              NOTIFICATION_DELIVERY_GUARD.GUARD_ID.asc())
          .limit(batchLimit);
      var rates = transaction.select(NOTIFICATION_RATE_LIMIT.RATE_LIMIT_ID)
          .from(NOTIFICATION_RATE_LIMIT)
          .where(NOTIFICATION_RATE_LIMIT.EXPIRES_AT.le(timestamp))
          .orderBy(NOTIFICATION_RATE_LIMIT.EXPIRES_AT.asc(),
              NOTIFICATION_RATE_LIMIT.RATE_LIMIT_ID.asc())
          .limit(batchLimit);
      var guardsDeleted = transaction.deleteFrom(NOTIFICATION_DELIVERY_GUARD)
          .where(NOTIFICATION_DELIVERY_GUARD.GUARD_ID.in(guards)).execute();
      var ratesDeleted = transaction.deleteFrom(NOTIFICATION_RATE_LIMIT)
          .where(NOTIFICATION_RATE_LIMIT.RATE_LIMIT_ID.in(rates)).execute();
      return new NotificationCleanupResult(guardsDeleted, ratesDeleted);
    });
  }

  private static void release(DSLContext transaction, NotificationDeliveryPermit permit) {
    transaction.update(NOTIFICATION_RATE_LIMIT)
        .set(NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT,
            NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT.minus(1L))
        .set(NOTIFICATION_RATE_LIMIT.VERSION, NOTIFICATION_RATE_LIMIT.VERSION.plus(1L))
        .where(NOTIFICATION_RATE_LIMIT.RATE_LIMIT_ID.eq(permit.rateId())
            .and(NOTIFICATION_RATE_LIMIT.DELIVERY_COUNT.gt(0L)))
        .execute();
    transaction.deleteFrom(NOTIFICATION_DELIVERY_GUARD)
        .where(NOTIFICATION_DELIVERY_GUARD.GUARD_ID.eq(permit.claimId()))
        .execute();
  }

  private static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}

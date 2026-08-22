package dev.christopherbell.notification.delivery;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL atomic notification dedupe and fixed-window rate-limit implementation. */
@PostgresPersistence
public class PostgresNotificationFanoutGuard implements NotificationFanoutPort {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final NotificationDeliveryProperties properties;
  private final String guardTable;
  private final String rateTable;

  public PostgresNotificationFanoutGuard(
      JdbcClient database,
      PostgresqlSchemaNames schemas,
      TransactionOperations transactions,
      NotificationDeliveryProperties properties) {
    this.database = database;
    this.transactions = transactions;
    this.properties = properties;
    guardTable = schemas.qualifiedTable("communication", "notification_delivery_guard");
    rateTable = schemas.qualifiedTable("communication", "notification_rate_limit");
  }

  @Override
  public Optional<NotificationDeliveryPermit> tryAcquire(
      NotificationEventIdentity identity, Instant now) {
    var result = transactions.execute(ignored -> tryAcquireInTransaction(identity, now));
    return result == null ? Optional.empty() : result;
  }

  private Optional<NotificationDeliveryPermit> tryAcquireInTransaction(
      NotificationEventIdentity identity, Instant now) {
    var claimId = hash(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), identity.targetId()));
    var claimed = database.sql("""
            insert into %s (
              guard_id, account_id, actor_account_id, notification_type, target_id, expires_at)
            values (:id, :accountId, :actorId, :type, :targetId, :expiresAt)
            on conflict (guard_id) do update set
              account_id = excluded.account_id,
              actor_account_id = excluded.actor_account_id,
              notification_type = excluded.notification_type,
              target_id = excluded.target_id,
              expires_at = excluded.expires_at,
              version = %s.version + 1
            where %s.expires_at <= :now
            returning guard_id
            """.formatted(guardTable, guardTable, guardTable))
        .param("id", claimId)
        .param("accountId", identity.recipientAccountId())
        .param("actorId", identity.actorAccountId())
        .param("type", identity.type().name())
        .param("targetId", identity.targetId())
        .param("expiresAt", now.plus(properties.dedupeWindow()).atOffset(ZoneOffset.UTC))
        .param("now", now.atOffset(ZoneOffset.UTC))
        .query(String.class).optional();
    if (claimed.isEmpty()) return Optional.empty();

    var bucket = Math.floorDiv(now.toEpochMilli(), properties.rateWindow().toMillis());
    var rateId = hash(String.join("\n",
        identity.recipientAccountId(), identity.actorAccountId(),
        identity.type().name(), Long.toString(bucket)));
    var count = database.sql("""
            insert into %s (
              rate_limit_id, account_id, actor_account_id, notification_type,
              delivery_count, expires_at)
            values (:id, :accountId, :actorId, :type, 1, :expiresAt)
            on conflict (rate_limit_id) do update set
              delivery_count = %s.delivery_count + 1,
              version = %s.version + 1
            returning delivery_count
            """.formatted(rateTable, rateTable, rateTable))
        .param("id", rateId)
        .param("accountId", identity.recipientAccountId())
        .param("actorId", identity.actorAccountId())
        .param("type", identity.type().name())
        .param("expiresAt", now.plus(properties.rateWindow()).atOffset(ZoneOffset.UTC))
        .query(Long.class).single();
    var permit = new NotificationDeliveryPermit(claimId, rateId);
    if (count > properties.maxEventsPerWindow()) {
      releaseInTransaction(permit);
      return Optional.empty();
    }
    return Optional.of(permit);
  }

  @Override
  public void release(NotificationDeliveryPermit permit) {
    if (permit != null) transactions.executeWithoutResult(ignored -> releaseInTransaction(permit));
  }

  private void releaseInTransaction(NotificationDeliveryPermit permit) {
    database.sql("""
            update %s set delivery_count = delivery_count - 1, version = version + 1
            where rate_limit_id = :rateId and delivery_count > 0
            """.formatted(rateTable))
        .param("rateId", permit.rateId()).update();
    database.sql("delete from %s where guard_id = :claimId".formatted(guardTable))
        .param("claimId", permit.claimId()).update();
  }

  @Override
  public NotificationCleanupResult deleteExpired(Instant cutoff, int batchLimit) {
    if (batchLimit < 1) throw new IllegalArgumentException("Cleanup batch limit must be positive");
    var result = transactions.execute(ignored -> deleteExpiredInTransaction(cutoff, batchLimit));
    if (result == null) throw new IllegalStateException("Notification cleanup returned no result");
    return result;
  }

  private NotificationCleanupResult deleteExpiredInTransaction(Instant cutoff, int batchLimit) {
    var timestamp = cutoff.atOffset(ZoneOffset.UTC);
    var guardsDeleted = deleteExpiredRows(
        guardTable, "guard_id", timestamp, batchLimit);
    var ratesDeleted = deleteExpiredRows(
        rateTable, "rate_limit_id", timestamp, batchLimit);
    return new NotificationCleanupResult(guardsDeleted, ratesDeleted);
  }

  private int deleteExpiredRows(
      String table, String idColumn, java.time.OffsetDateTime cutoff, int limit) {
    return database.sql("""
            with candidates as (
              select %s from %s where expires_at <= :cutoff
              order by expires_at asc, %s asc limit :limit for update
            )
            delete from %s target using candidates
            where target.%s = candidates.%s and target.expires_at <= :cutoff
            """.formatted(idColumn, table, idColumn, table, idColumn, idColumn))
        .param("cutoff", cutoff).param("limit", limit).update();
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

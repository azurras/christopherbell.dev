package dev.christopherbell.federation.outbound;

import static dev.christopherbell.persistence.jooq.federation.Tables.FEDERATION_DELIVERY_JOB;
import static dev.christopherbell.persistence.jooq.federation.Tables.FEDERATION_SCAN_STATE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import dev.christopherbell.persistence.jooq.federation.tables.records.FederationDeliveryJobRecord;
import dev.christopherbell.post.model.Post;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** PostgreSQL owner of federation scan, enqueue, claim, and exact-owner transitions. */
@PostgresPersistence
final class PostgresFederationDeliveryJobRepository implements FederationDeliveryStore {
  private final DSLContext database;

  PostgresFederationDeliveryJobRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public FederationScanCursor loadCursor() {
    return database.selectFrom(FEDERATION_SCAN_STATE)
        .where(FEDERATION_SCAN_STATE.SCAN_STATE_ID.eq(FederationScanState.OUTBOUND_CREATE))
        .fetchOptional(record -> record.getCreatedOn() == null || record.getPostId() == null
            ? null : new FederationScanCursor(record.getCreatedOn().toInstant(), record.getPostId()))
        .orElse(null);
  }

  @Override
  public void enqueueIfAbsent(Post post, ControlledPeer peer, Instant now) {
    database.insertInto(FEDERATION_DELIVERY_JOB)
        .set(FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID, stableJobId(post.getId(), peer.name()))
        .set(FEDERATION_DELIVERY_JOB.POST_ID, post.getId())
        .set(FEDERATION_DELIVERY_JOB.ACCOUNT_ID, post.getAccountId())
        .set(FEDERATION_DELIVERY_JOB.PEER_NAME, peer.name())
        .set(FEDERATION_DELIVERY_JOB.PEER_INBOX, peer.inbox().toString())
        .set(FEDERATION_DELIVERY_JOB.STATE, FederationDeliveryState.PENDING.name())
        .set(FEDERATION_DELIVERY_JOB.ATTEMPTS, 0)
        .set(FEDERATION_DELIVERY_JOB.NEXT_ATTEMPT_ON, timestamp(now))
        .set(FEDERATION_DELIVERY_JOB.CREATED_ON, timestamp(now))
        .set(FEDERATION_DELIVERY_JOB.UPDATED_ON, timestamp(now))
        .onConflict(FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID)
        .doNothing()
        .execute();
  }

  @Override
  public void saveCursor(FederationScanCursor cursor, Instant now) {
    database.insertInto(FEDERATION_SCAN_STATE)
        .set(FEDERATION_SCAN_STATE.SCAN_STATE_ID, FederationScanState.OUTBOUND_CREATE)
        .set(FEDERATION_SCAN_STATE.CREATED_ON, timestamp(cursor.createdOn()))
        .set(FEDERATION_SCAN_STATE.POST_ID, cursor.postId())
        .set(FEDERATION_SCAN_STATE.UPDATED_ON, timestamp(now))
        .onConflict(FEDERATION_SCAN_STATE.SCAN_STATE_ID)
        .doUpdate()
        .set(FEDERATION_SCAN_STATE.CREATED_ON, timestamp(cursor.createdOn()))
        .set(FEDERATION_SCAN_STATE.POST_ID, cursor.postId())
        .set(FEDERATION_SCAN_STATE.UPDATED_ON, timestamp(now))
        .set(FEDERATION_SCAN_STATE.VERSION, FEDERATION_SCAN_STATE.VERSION.plus(1L))
        .execute();
  }

  @Override
  public Optional<FederationDeliveryJob> claimDue(
      String owner, Instant now, Instant leaseUntil) {
    var leaseDuration = Duration.between(now, leaseUntil);
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("Federation delivery lease must be positive");
    }
    return database.transactionResult(configuration -> {
      var transaction = DSL.using(configuration);
      var databaseTime = DSL.currentOffsetDateTime();
      var due = FEDERATION_DELIVERY_JOB.STATE.in(
              FederationDeliveryState.PENDING.name(), FederationDeliveryState.RETRY.name())
          .and(FEDERATION_DELIVERY_JOB.NEXT_ATTEMPT_ON.le(databaseTime))
          .or(FEDERATION_DELIVERY_JOB.STATE.eq(FederationDeliveryState.CLAIMED.name())
              .and(FEDERATION_DELIVERY_JOB.CLAIM_UNTIL.le(databaseTime)));
      var id = transaction.select(FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID)
          .from(FEDERATION_DELIVERY_JOB)
          .where(due)
          .orderBy(FEDERATION_DELIVERY_JOB.NEXT_ATTEMPT_ON.asc().nullsFirst(),
              FEDERATION_DELIVERY_JOB.CREATED_ON.asc(),
              FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID.asc())
          .limit(1)
          .forUpdate()
          .skipLocked()
          .fetchOne(FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID);
      if (id == null) return Optional.empty();
      return transaction.update(FEDERATION_DELIVERY_JOB)
          .set(FEDERATION_DELIVERY_JOB.STATE, FederationDeliveryState.CLAIMED.name())
          .set(FEDERATION_DELIVERY_JOB.CLAIM_OWNER, owner)
          .set(FEDERATION_DELIVERY_JOB.CLAIM_UNTIL, databaseLeaseUntil(leaseDuration))
          .set(FEDERATION_DELIVERY_JOB.UPDATED_ON, databaseTime)
          .set(FEDERATION_DELIVERY_JOB.ATTEMPTS,
              FEDERATION_DELIVERY_JOB.ATTEMPTS.plus(1))
          .set(FEDERATION_DELIVERY_JOB.VERSION, FEDERATION_DELIVERY_JOB.VERSION.plus(1L))
          .where(FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID.eq(id))
          .returning()
          .fetchOptional(PostgresFederationDeliveryJobRepository::map);
    });
  }

  @Override
  public boolean succeed(String jobId, String owner, int status, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.SUCCEEDED, status,
        null, "DELIVERED", now);
  }

  @Override
  public boolean retry(
      String jobId, String owner, Integer status, Instant nextAttempt, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.RETRY, status,
        nextAttempt, "RETRYABLE", now);
  }

  @Override
  public boolean dead(
      String jobId, String owner, Integer status, String reason, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.DEAD, status,
        null, bounded(reason), now);
  }

  @Override
  public boolean cancel(String jobId, String owner, String reason, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.CANCELLED, null,
        null, bounded(reason), now);
  }

  private boolean transition(
      String jobId,
      String owner,
      FederationDeliveryState state,
      Integer status,
      Instant nextAttempt,
      String outcome,
      Instant now) {
    return database.update(FEDERATION_DELIVERY_JOB)
        .set(FEDERATION_DELIVERY_JOB.STATE, state.name())
        .set(FEDERATION_DELIVERY_JOB.NEXT_ATTEMPT_ON,
            nextAttempt == null
                ? FEDERATION_DELIVERY_JOB.NEXT_ATTEMPT_ON
                : DSL.val(timestamp(nextAttempt)))
        .set(FEDERATION_DELIVERY_JOB.LAST_STATUS, status)
        .set(FEDERATION_DELIVERY_JOB.LAST_OUTCOME, outcome)
        .set(FEDERATION_DELIVERY_JOB.UPDATED_ON, DSL.currentOffsetDateTime())
        .setNull(FEDERATION_DELIVERY_JOB.CLAIM_OWNER)
        .setNull(FEDERATION_DELIVERY_JOB.CLAIM_UNTIL)
        .set(FEDERATION_DELIVERY_JOB.VERSION, FEDERATION_DELIVERY_JOB.VERSION.plus(1L))
        .where(FEDERATION_DELIVERY_JOB.DELIVERY_JOB_ID.eq(jobId)
            .and(FEDERATION_DELIVERY_JOB.STATE.eq(FederationDeliveryState.CLAIMED.name()))
            .and(FEDERATION_DELIVERY_JOB.CLAIM_OWNER.eq(owner))
            .and(FEDERATION_DELIVERY_JOB.CLAIM_UNTIL.gt(DSL.currentOffsetDateTime())))
        .execute() == 1;
  }

  private static FederationDeliveryJob map(FederationDeliveryJobRecord record) {
    return new FederationDeliveryJob(
        record.getDeliveryJobId(), record.getPostId(), record.getAccountId(),
        record.getPeerName(), record.getPeerInbox(), FederationDeliveryState.valueOf(record.getState()),
        record.getAttempts(), instant(record.getNextAttemptOn()), record.getClaimOwner(),
        instant(record.getClaimUntil()), record.getLastStatus(), record.getLastOutcome(),
        record.getCreatedOn().toInstant(), record.getUpdatedOn().toInstant());
  }

  private static String stableJobId(String postId, String peerName) {
    try {
      var input = (postId + "\0" + peerName + "\0Create").getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static String bounded(String reason) {
    if (reason == null || reason.isBlank() || reason.length() > 64) {
      throw new IllegalArgumentException("Federation delivery outcome must be 1-64 characters");
    }
    return reason;
  }

  private static OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static Field<OffsetDateTime> databaseLeaseUntil(Duration leaseDuration) {
    return DSL.field(
        "{0} + ({1} * interval '1 millisecond')",
        OffsetDateTime.class,
        DSL.currentOffsetDateTime(),
        DSL.val(leaseDuration.toMillis()));
  }
}

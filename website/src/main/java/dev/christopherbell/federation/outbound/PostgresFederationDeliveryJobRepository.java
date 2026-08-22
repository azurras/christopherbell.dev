package dev.christopherbell.federation.outbound;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.federation.configuration.FederationOutboundProperties.ControlledPeer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL owner of federation scan, enqueue, claim, and exact-owner transitions. */
@PostgresPersistence
class PostgresFederationDeliveryJobRepository implements FederationDeliveryStore {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String jobTable;
  private final String scanTable;

  PostgresFederationDeliveryJobRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    jobTable = schemas.qualifiedTable("federation", "federation_delivery_job");
    scanTable = schemas.qualifiedTable("federation", "federation_scan_state");
  }

  @Override
  public FederationScanCursor loadCursor() {
    return database.sql("""
            select created_on, post_id from %s where scan_state_id = :id
            """.formatted(scanTable)).param("id", FederationScanState.OUTBOUND_CREATE)
        .query((row, ignored) -> {
          var created = row.getObject("created_on", OffsetDateTime.class);
          var postId = row.getString("post_id");
          return created == null || postId == null
              ? null : new FederationScanCursor(created.toInstant(), postId);
        }).optional().orElse(null);
  }

  @Override
  public void enqueueIfAbsent(String postId, String accountId, ControlledPeer peer, Instant now) {
    database.sql("""
            insert into %s (
              delivery_job_id, post_id, account_id, peer_name, peer_inbox, state,
              attempts, next_attempt_on, created_on, updated_on)
            values (:id, :postId, :accountId, :peerName, :peerInbox, 'PENDING', 0, :now, :now, :now)
            on conflict (delivery_job_id) do nothing
            """.formatted(jobTable))
        .param("id", stableJobId(postId, peer.name())).param("postId", postId)
        .param("accountId", accountId).param("peerName", peer.name())
        .param("peerInbox", peer.inbox().toString())
        .param("now", timestamp(now)).update();
  }

  @Override
  public void saveCursor(FederationScanCursor cursor, Instant now) {
    database.sql("""
            insert into %s (scan_state_id, created_on, post_id, updated_on)
            values (:id, :createdOn, :postId, :updatedOn)
            on conflict (scan_state_id) do update set
              created_on = excluded.created_on, post_id = excluded.post_id,
              updated_on = excluded.updated_on, version = %s.version + 1
            """.formatted(scanTable, scanTable))
        .param("id", FederationScanState.OUTBOUND_CREATE)
        .param("createdOn", timestamp(cursor.createdOn())).param("postId", cursor.postId())
        .param("updatedOn", timestamp(now)).update();
  }

  @Override
  public Optional<FederationDeliveryJob> claimDue(String owner, Instant now, Instant leaseUntil) {
    var duration = Duration.between(now, leaseUntil);
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Federation delivery lease must be positive");
    }
    var claimed = transactions.execute(ignored -> {
      var id = database.sql("""
              select delivery_job_id from %s
              where (state in ('PENDING', 'RETRY') and next_attempt_on <= current_timestamp)
                 or (state = 'CLAIMED' and claim_until <= current_timestamp)
              order by next_attempt_on asc nulls first, created_on asc, delivery_job_id asc
              limit 1 for update skip locked
              """.formatted(jobTable)).query(String.class).optional();
      if (id.isEmpty()) return Optional.<FederationDeliveryJob>empty();
      return database.sql("""
              update %s set state = 'CLAIMED', claim_owner = :owner,
                claim_until = current_timestamp + (:milliseconds * interval '1 millisecond'),
                updated_on = current_timestamp, attempts = attempts + 1, version = version + 1
              where delivery_job_id = :id returning *
              """.formatted(jobTable)).param("owner", owner)
          .param("milliseconds", duration.toMillis()).param("id", id.orElseThrow())
          .query(PostgresFederationDeliveryJobRepository::map).optional();
    });
    return claimed == null ? Optional.empty() : claimed;
  }

  @Override
  public boolean succeed(String jobId, String owner, int status, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.SUCCEEDED, status,
        null, "DELIVERED");
  }

  @Override
  public boolean retry(String jobId, String owner, Integer status, Instant nextAttempt, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.RETRY, status,
        nextAttempt, "RETRYABLE");
  }

  @Override
  public boolean dead(String jobId, String owner, Integer status, String reason, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.DEAD, status, null, bounded(reason));
  }

  @Override
  public boolean cancel(String jobId, String owner, String reason, Instant now) {
    return transition(jobId, owner, FederationDeliveryState.CANCELLED, null, null, bounded(reason));
  }

  private boolean transition(
      String jobId, String owner, FederationDeliveryState state, Integer status,
      Instant nextAttempt, String outcome) {
    return database.sql("""
            update %s set state = :state,
              next_attempt_on = coalesce(:nextAttempt, next_attempt_on),
              last_status = :status, last_outcome = :outcome, updated_on = current_timestamp,
              claim_owner = null, claim_until = null, version = version + 1
            where delivery_job_id = :id and state = 'CLAIMED' and claim_owner = :owner
              and claim_until > current_timestamp
            """.formatted(jobTable))
        .paramSource(new MapSqlParameterSource()
            .addValue("state", state.name())
            .addValue("nextAttempt", timestamp(nextAttempt), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("status", status, Types.INTEGER).addValue("outcome", outcome)
            .addValue("id", jobId).addValue("owner", owner))
        .update() == 1;
  }

  private static FederationDeliveryJob map(ResultSet row, int rowNumber) throws SQLException {
    Integer status = (Integer) row.getObject("last_status");
    return new FederationDeliveryJob(
        row.getString("delivery_job_id"), row.getString("post_id"), row.getString("account_id"),
        row.getString("peer_name"), row.getString("peer_inbox"),
        FederationDeliveryState.valueOf(row.getString("state")), row.getInt("attempts"),
        instant(row, "next_attempt_on"), row.getString("claim_owner"),
        instant(row, "claim_until"), status, row.getString("last_outcome"),
        instant(row, "created_on"), instant(row, "updated_on"));
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

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

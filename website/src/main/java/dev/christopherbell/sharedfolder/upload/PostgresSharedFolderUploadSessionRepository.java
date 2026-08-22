package dev.christopherbell.sharedfolder.upload;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlLeaseFields;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL repository for owner-scoped resumable-upload metadata and claims. */
@PostgresPersistence
public class PostgresSharedFolderUploadSessionRepository
    implements SharedFolderUploadSessionRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String sessionTable;
  private final String chunkTable;

  public PostgresSharedFolderUploadSessionRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    sessionTable = schemas.qualifiedTable("shared_folder", "upload_session");
    chunkTable = schemas.qualifiedTable("shared_folder", "upload_chunk");
  }

  @Override
  public SharedFolderUploadSession save(SharedFolderUploadSession session) {
    return transactions.execute(status -> {
      String parent = PostgresqlRelativePath.requireRootAllowed(
          session.getParentPath(), "Upload parent path");
      String staging = PostgresqlRelativePath.require(
          session.getStagingKey(), "Upload staging key");
      String quarantine = session.getFinalizingQuarantineKey() == null ? null
          : PostgresqlRelativePath.require(
              session.getFinalizingQuarantineKey(), "Upload quarantine key");
      var parameters = parameters(session, parent, staging, quarantine);
      if (session.getVersion() == null) {
        database.sql("""
                insert into %s (
                  upload_session_id, version, owner_id, parent_path, entry_name,
                  expected_bytes, expected_sha256, target_observed_token, next_offset,
                  staging_key, append_lease_token, append_lease_expires_at, append_offset,
                  append_length, append_digest, append_chunk_key, finalizing_identity,
                  finalizing_replace, finalizing_target_identity, finalizing_quarantine_key,
                  finalization_state, finalization_lease_token,
                  finalization_lease_expires_at, expires_at, delete_at,
                  maintenance_retry_at, maintenance_attempts, state, created_at, updated_at)
                values (
                  :id, 0, :ownerId, :parentPath, :entryName, :expectedBytes,
                  :expectedSha256, :targetObservedToken, :nextOffset, :stagingKey,
                  :appendLeaseToken, :appendLeaseExpiresAt, :appendOffset, :appendLength,
                  :appendDigest, :appendChunkKey, :finalizingIdentity, :finalizingReplace,
                  :finalizingTargetIdentity, :finalizingQuarantineKey, :finalizationState,
                  :finalizationLeaseToken, :finalizationLeaseExpiresAt, :expiresAt,
                  :deleteAt, :maintenanceRetryAt, :maintenanceAttempts, :state,
                  :createdAt, :updatedAt)
                """.formatted(sessionTable)).paramSource(parameters).update();
      } else {
        int changed = database.sql("""
                update %s set version = :nextVersion, owner_id = :ownerId,
                  parent_path = :parentPath, entry_name = :entryName,
                  expected_bytes = :expectedBytes, expected_sha256 = :expectedSha256,
                  target_observed_token = :targetObservedToken, next_offset = :nextOffset,
                  staging_key = :stagingKey, append_lease_token = :appendLeaseToken,
                  append_lease_expires_at = :appendLeaseExpiresAt,
                  append_offset = :appendOffset, append_length = :appendLength,
                  append_digest = :appendDigest, append_chunk_key = :appendChunkKey,
                  finalizing_identity = :finalizingIdentity,
                  finalizing_replace = :finalizingReplace,
                  finalizing_target_identity = :finalizingTargetIdentity,
                  finalizing_quarantine_key = :finalizingQuarantineKey,
                  finalization_state = :finalizationState,
                  finalization_lease_token = :finalizationLeaseToken,
                  finalization_lease_expires_at = :finalizationLeaseExpiresAt,
                  expires_at = :expiresAt, delete_at = :deleteAt,
                  maintenance_retry_at = :maintenanceRetryAt,
                  maintenance_attempts = :maintenanceAttempts, state = :state,
                  created_at = :createdAt, updated_at = :updatedAt
                where upload_session_id = :id and version = :expectedVersion
                """.formatted(sessionTable)).paramSource(parameters
                    .addValue("nextVersion", Math.incrementExact(session.getVersion()))
                    .addValue("expectedVersion", session.getVersion())).update();
        if (changed != 1) {
          throw new OptimisticLockingFailureException("Upload session changed during save.");
        }
      }
      database.sql("delete from %s where upload_session_id = :id".formatted(chunkTable))
          .param("id", session.getId()).update();
      var keys = new HashSet<String>();
      keys.addAll(session.getChunkDigests().keySet());
      keys.addAll(session.getChunkLengths().keySet());
      for (String key : keys) {
        database.sql("""
                insert into %s (upload_session_id, chunk_key, digest, chunk_length)
                values (:id, :key, :digest, :length)
                """.formatted(chunkTable))
            .param("id", session.getId()).param("key", key)
            .param("digest", session.getChunkDigests().get(key), Types.VARCHAR)
            .param("length", session.getChunkLengths().get(key), Types.BIGINT).update();
      }
      return findById(session.getId()).orElseThrow();
    });
  }

  @Override
  public Optional<SharedFolderUploadSession> findById(String id) {
    return database.sql("select * from %s where upload_session_id = :id".formatted(sessionTable))
        .param("id", id).query(this::map).optional();
  }

  @Override
  public void deleteById(String id) {
    database.sql("delete from %s where upload_session_id = :id".formatted(sessionTable))
        .param("id", id).update();
  }

  @Override
  public long countByOwnerIdAndStateIn(
      String ownerId, Collection<SharedFolderUploadState> states) {
    return database.sql("""
            select count(*) from %s where owner_id = :ownerId and state in (:states)
            """.formatted(sessionTable)).param("ownerId", ownerId)
        .param("states", states.stream().map(Enum::name).toList())
        .query(Long.class).single();
  }

  @Override
  public Slice<SharedFolderUploadSession> findByOwnerIdOrderByIdAsc(
      String ownerId, Pageable pageable) {
    return slice(
        "owner_id = :ownerId", new MapSqlParameterSource("ownerId", ownerId), pageable,
        "upload_session_id asc");
  }

  @Override
  public Slice<SharedFolderUploadSession> findDueForMaintenance(
      Instant dueAtOrBefore, Pageable pageable) {
    return slice("""
        ((state = :active and expires_at <= :dueAt)
          or (state = :expired
            and (maintenance_retry_at is null or maintenance_retry_at <= :dueAt)))
        """, new MapSqlParameterSource()
            .addValue("active", SharedFolderUploadState.ACTIVE.name())
            .addValue("expired", SharedFolderUploadState.EXPIRED.name())
            .addValue("dueAt", offset(dueAtOrBefore), Types.TIMESTAMP_WITH_TIMEZONE),
        pageable,
        "maintenance_retry_at asc nulls first, expires_at asc, upload_session_id asc");
  }

  @Override
  public long expireActive(String id, Instant expiresAtOrBefore, Instant updatedAt) {
    return database.sql("""
            update %s set state = :expired, maintenance_retry_at = :updatedAt,
              maintenance_attempts = 0, updated_at = :updatedAt, version = version + 1
            where upload_session_id = :id and state = :active and expires_at <= :expiresAt
            """.formatted(sessionTable)).param("expired", SharedFolderUploadState.EXPIRED.name())
        .param("updatedAt", offset(updatedAt)).param("id", id)
        .param("active", SharedFolderUploadState.ACTIVE.name())
        .param("expiresAt", offset(expiresAtOrBefore)).update();
  }

  @Override
  public long deferExpiredMaintenance(
      String id, int expectedAttempts, Instant retryAt, int newAttempts, Instant updatedAt) {
    return database.sql("""
            update %s set maintenance_retry_at = :retryAt,
              maintenance_attempts = :newAttempts, updated_at = :updatedAt,
              version = version + 1
            where upload_session_id = :id and state = :expired
              and maintenance_attempts = :expectedAttempts
            """.formatted(sessionTable)).param("retryAt", offset(retryAt))
        .param("newAttempts", newAttempts).param("updatedAt", offset(updatedAt))
        .param("id", id).param("expired", SharedFolderUploadState.EXPIRED.name())
        .param("expectedAttempts", expectedAttempts).update();
  }

  @Override
  public Optional<Instant> acquireAppendLease(
      String id, String token, long appendOffset, Duration duration) {
    return lease("append", "append_lease_token is null and append_lease_expires_at is null",
        id, null, token, appendOffset, null, duration);
  }

  @Override
  public Optional<Instant> acquireFinalizationLease(
      String id, String token, SharedFolderUploadFinalizationState state, Duration duration) {
    return lease("finalization",
        "finalization_lease_token is null and finalization_lease_expires_at is null",
        id, null, token, null, state, duration);
  }

  @Override
  public boolean relinquishAppendLease(String id, String token, long appendOffset) {
    return relinquish("append", id, token, appendOffset, null);
  }

  @Override
  public boolean relinquishFinalizationLease(
      String id, String token, SharedFolderUploadFinalizationState state) {
    return relinquish("finalization", id, token, null, state);
  }

  @Override
  public Optional<Instant> renewFinalizationLease(
      String id, String token, SharedFolderUploadFinalizationState state, Duration duration) {
    return lease("finalization", "finalization_lease_token = :expectedToken"
        + " and finalization_lease_expires_at > current_timestamp",
        id, token, token, null, state, duration);
  }

  @Override
  public Optional<Instant> claimExpiredFinalizationLease(
      String id, String oldToken, SharedFolderUploadFinalizationState state,
      String newToken, Duration duration) {
    String token = oldToken == null
        ? "finalization_lease_token is null" : "finalization_lease_token = :expectedToken";
    return lease("finalization", token
        + " and (finalization_lease_expires_at is null"
        + " or finalization_lease_expires_at <= current_timestamp)",
        id, oldToken, newToken, null, state, duration);
  }

  @Override
  public Optional<Instant> renewAppendLease(
      String id, String token, long appendOffset, Duration duration) {
    return lease("append", "append_lease_token = :expectedToken"
        + " and append_lease_expires_at > current_timestamp",
        id, token, token, appendOffset, null, duration);
  }

  @Override
  public Optional<Instant> claimExpiredAppendLease(
      String id, String oldToken, long appendOffset, String newToken, Duration duration) {
    String token = oldToken == null
        ? "append_lease_token is null" : "append_lease_token = :expectedToken";
    return lease("append", token
        + " and (append_lease_expires_at is null"
        + " or append_lease_expires_at <= current_timestamp)",
        id, oldToken, newToken, appendOffset, null, duration);
  }

  private Optional<Instant> lease(
      String prefix, String predicate, String id, String expectedToken, String token,
      Long appendOffset, SharedFolderUploadFinalizationState finalizationState,
      Duration duration) {
    String state = prefix.equals("append")
        ? SharedFolderUploadState.APPENDING.name() : SharedFolderUploadState.FINALIZING.name();
    String statePredicate = prefix.equals("append")
        ? "and append_offset = :appendOffset" : "and finalization_state = :finalizationState";
    var statement = database.sql("""
            update %s set
              %s_lease_expires_at = current_timestamp + (:micros * interval '1 microsecond'),
              %s_lease_token = :token, updated_at = current_timestamp, version = version + 1
            where upload_session_id = :id and state = :state %s and %s
            returning %s_lease_expires_at
            """.formatted(sessionTable, prefix, prefix, statePredicate, predicate, prefix))
        .param("micros", PostgresqlLeaseFields.microseconds(duration))
        .param("token", token).param("id", id).param("state", state);
    if (expectedToken != null) statement.param("expectedToken", expectedToken);
    if (appendOffset != null) statement.param("appendOffset", appendOffset);
    if (finalizationState != null) statement.param("finalizationState", finalizationState.name());
    return statement.query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
  }

  private boolean relinquish(
      String prefix, String id, String token, Long appendOffset,
      SharedFolderUploadFinalizationState finalizationState) {
    String state = prefix.equals("append")
        ? SharedFolderUploadState.APPENDING.name() : SharedFolderUploadState.FINALIZING.name();
    String statePredicate = prefix.equals("append")
        ? "and append_offset = :appendOffset" : "and finalization_state = :finalizationState";
    var statement = database.sql("""
            update %s set %s_lease_expires_at = current_timestamp,
              updated_at = current_timestamp, version = version + 1
            where upload_session_id = :id and state = :state %s
              and %s_lease_token = :token and %s_lease_expires_at > current_timestamp
            """.formatted(sessionTable, prefix, statePredicate, prefix, prefix))
        .param("id", id).param("state", state).param("token", token);
    if (appendOffset != null) statement.param("appendOffset", appendOffset);
    if (finalizationState != null) statement.param("finalizationState", finalizationState.name());
    return statement.update() == 1;
  }

  private Slice<SharedFolderUploadSession> slice(
      String predicate, MapSqlParameterSource parameters, Pageable pageable, String order) {
    int size = pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE - 1;
    int offset = pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0;
    List<SharedFolderUploadSession> rows = database.sql("""
            select * from %s where %s order by %s limit :limit offset :offset
            """.formatted(sessionTable, predicate, order))
        .paramSource(parameters.addValue("limit", pageable.isPaged() ? size + 1 : size)
            .addValue("offset", offset)).query(this::map).list();
    boolean next = pageable.isPaged() && rows.size() > size;
    return new SliceImpl<>(
        next ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows), pageable, next);
  }

  private SharedFolderUploadSession map(ResultSet row, int rowNumber) throws SQLException {
    var session = new SharedFolderUploadSession();
    session.setId(row.getString("upload_session_id"));
    session.setVersion(row.getLong("version"));
    session.setOwnerId(row.getString("owner_id"));
    session.setParentPath(row.getString("parent_path"));
    session.setName(row.getString("entry_name"));
    session.setExpectedBytes(row.getLong("expected_bytes"));
    session.setExpectedSha256(row.getString("expected_sha256"));
    session.setTargetObservedToken(row.getString("target_observed_token"));
    session.setNextOffset(row.getLong("next_offset"));
    var digests = new HashMap<String, String>();
    var lengths = new HashMap<String, Long>();
    database.sql("""
            select chunk_key, digest, chunk_length from %s
            where upload_session_id = :id order by chunk_key
            """.formatted(chunkTable)).param("id", session.getId())
        .query((chunk, ignored) -> {
              String key = chunk.getString("chunk_key");
              String digest = chunk.getString("digest");
              Long length = (Long) chunk.getObject("chunk_length");
              if (digest != null) digests.put(key, digest);
              if (length != null) lengths.put(key, length);
              return key;
            }).list();
    session.setChunkDigests(digests);
    session.setChunkLengths(lengths);
    session.setStagingKey(row.getString("staging_key"));
    session.setAppendLeaseToken(row.getString("append_lease_token"));
    session.setAppendLeaseExpiresAt(instant(row, "append_lease_expires_at"));
    session.setAppendOffset((Long) row.getObject("append_offset"));
    session.setAppendLength((Long) row.getObject("append_length"));
    session.setAppendDigest(row.getString("append_digest"));
    session.setAppendChunkKey(row.getString("append_chunk_key"));
    session.setFinalizingIdentity(row.getString("finalizing_identity"));
    session.setFinalizingReplace((Boolean) row.getObject("finalizing_replace"));
    session.setFinalizingTargetIdentity(row.getString("finalizing_target_identity"));
    session.setFinalizingQuarantineKey(row.getString("finalizing_quarantine_key"));
    String finalizationState = row.getString("finalization_state");
    session.setFinalizationState(finalizationState == null ? null
        : SharedFolderUploadFinalizationState.valueOf(finalizationState));
    session.setFinalizationLeaseToken(row.getString("finalization_lease_token"));
    session.setFinalizationLeaseExpiresAt(instant(row, "finalization_lease_expires_at"));
    session.setExpiresAt(instant(row, "expires_at"));
    session.setDeleteAt(instant(row, "delete_at"));
    session.setMaintenanceRetryAt(instant(row, "maintenance_retry_at"));
    session.setMaintenanceAttempts(row.getInt("maintenance_attempts"));
    session.setState(SharedFolderUploadState.valueOf(row.getString("state")));
    session.setCreatedAt(instant(row, "created_at"));
    session.setUpdatedAt(instant(row, "updated_at"));
    return session;
  }

  private static MapSqlParameterSource parameters(
      SharedFolderUploadSession session, String parent, String staging, String quarantine) {
    return new MapSqlParameterSource()
        .addValue("id", session.getId()).addValue("ownerId", session.getOwnerId())
        .addValue("parentPath", parent).addValue("entryName", session.getName())
        .addValue("expectedBytes", session.getExpectedBytes())
        .addValue("expectedSha256", session.getExpectedSha256())
        .addValue("targetObservedToken", session.getTargetObservedToken(), Types.VARCHAR)
        .addValue("nextOffset", session.getNextOffset()).addValue("stagingKey", staging)
        .addValue("appendLeaseToken", session.getAppendLeaseToken(), Types.VARCHAR)
        .addValue("appendLeaseExpiresAt", offset(session.getAppendLeaseExpiresAt()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("appendOffset", session.getAppendOffset(), Types.BIGINT)
        .addValue("appendLength", session.getAppendLength(), Types.BIGINT)
        .addValue("appendDigest", session.getAppendDigest(), Types.VARCHAR)
        .addValue("appendChunkKey", session.getAppendChunkKey(), Types.VARCHAR)
        .addValue("finalizingIdentity", session.getFinalizingIdentity(), Types.VARCHAR)
        .addValue("finalizingReplace", session.getFinalizingReplace(), Types.BOOLEAN)
        .addValue("finalizingTargetIdentity", session.getFinalizingTargetIdentity(), Types.VARCHAR)
        .addValue("finalizingQuarantineKey", quarantine, Types.VARCHAR)
        .addValue("finalizationState", session.getFinalizationState() == null ? null
            : session.getFinalizationState().name(), Types.VARCHAR)
        .addValue("finalizationLeaseToken", session.getFinalizationLeaseToken(), Types.VARCHAR)
        .addValue("finalizationLeaseExpiresAt", offset(session.getFinalizationLeaseExpiresAt()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("expiresAt", offset(session.getExpiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("deleteAt", offset(session.getDeleteAt()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("maintenanceRetryAt", offset(session.getMaintenanceRetryAt()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("maintenanceAttempts", session.getMaintenanceAttempts())
        .addValue("state", session.getState().name())
        .addValue("createdAt", offset(session.getCreatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("updatedAt", offset(session.getUpdatedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

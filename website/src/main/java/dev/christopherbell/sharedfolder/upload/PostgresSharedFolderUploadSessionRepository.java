package dev.christopherbell.sharedfolder.upload;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.UPLOAD_CHUNK;
import static dev.christopherbell.persistence.jooq.shared_folder.Tables.UPLOAD_SESSION;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlLeaseFields;
import java.time.Duration;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.persistence.jooq.shared_folder.tables.records.UploadSessionRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

/** PostgreSQL repository for owner-scoped resumable-upload metadata and claims. */
@PostgresPersistence
public class PostgresSharedFolderUploadSessionRepository
    implements SharedFolderUploadSessionRepository {
  private final DSLContext database;

  public PostgresSharedFolderUploadSessionRepository(DSLContext database) {
    this.database = database;
  }

  @Override public SharedFolderUploadSession save(SharedFolderUploadSession session) {
    return database.transactionResult(configuration -> {
      DSLContext transaction = DSL.using(configuration);
      String parent = PostgresqlRelativePath.requireRootAllowed(session.getParentPath(), "Upload parent path");
      String staging = PostgresqlRelativePath.require(session.getStagingKey(), "Upload staging key");
      String quarantine = session.getFinalizingQuarantineKey() == null ? null
          : PostgresqlRelativePath.require(session.getFinalizingQuarantineKey(), "Upload quarantine key");
      if (session.getVersion() == null) {
        transaction.insertInto(UPLOAD_SESSION).set(UPLOAD_SESSION.UPLOAD_SESSION_ID, session.getId())
            .set(UPLOAD_SESSION.VERSION, 0L).set(UPLOAD_SESSION.OWNER_ID, session.getOwnerId())
            .set(UPLOAD_SESSION.PARENT_PATH, parent).set(UPLOAD_SESSION.ENTRY_NAME, session.getName())
            .set(UPLOAD_SESSION.EXPECTED_BYTES, session.getExpectedBytes())
            .set(UPLOAD_SESSION.EXPECTED_SHA256, session.getExpectedSha256())
            .set(UPLOAD_SESSION.TARGET_OBSERVED_TOKEN, session.getTargetObservedToken())
            .set(UPLOAD_SESSION.NEXT_OFFSET, session.getNextOffset()).set(UPLOAD_SESSION.STAGING_KEY, staging)
            .set(UPLOAD_SESSION.APPEND_LEASE_TOKEN, session.getAppendLeaseToken())
            .set(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT, offset(session.getAppendLeaseExpiresAt()))
            .set(UPLOAD_SESSION.APPEND_OFFSET, session.getAppendOffset())
            .set(UPLOAD_SESSION.APPEND_LENGTH, session.getAppendLength())
            .set(UPLOAD_SESSION.APPEND_DIGEST, session.getAppendDigest())
            .set(UPLOAD_SESSION.APPEND_CHUNK_KEY, session.getAppendChunkKey())
            .set(UPLOAD_SESSION.FINALIZING_IDENTITY, session.getFinalizingIdentity())
            .set(UPLOAD_SESSION.FINALIZING_REPLACE, session.getFinalizingReplace())
            .set(UPLOAD_SESSION.FINALIZING_TARGET_IDENTITY, session.getFinalizingTargetIdentity())
            .set(UPLOAD_SESSION.FINALIZING_QUARANTINE_KEY, quarantine)
            .set(UPLOAD_SESSION.FINALIZATION_STATE,
                session.getFinalizationState() == null ? null : session.getFinalizationState().name())
            .set(UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN, session.getFinalizationLeaseToken())
            .set(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT, offset(session.getFinalizationLeaseExpiresAt()))
            .set(UPLOAD_SESSION.EXPIRES_AT, offset(session.getExpiresAt()))
            .set(UPLOAD_SESSION.DELETE_AT, offset(session.getDeleteAt()))
            .set(UPLOAD_SESSION.MAINTENANCE_RETRY_AT, offset(session.getMaintenanceRetryAt()))
            .set(UPLOAD_SESSION.MAINTENANCE_ATTEMPTS, session.getMaintenanceAttempts())
            .set(UPLOAD_SESSION.STATE, session.getState().name())
            .set(UPLOAD_SESSION.CREATED_AT, offset(session.getCreatedAt()))
            .set(UPLOAD_SESSION.UPDATED_AT, offset(session.getUpdatedAt())).execute();
      } else {
        long nextVersion = Math.incrementExact(session.getVersion());
        int changed = transaction.update(UPLOAD_SESSION).set(UPLOAD_SESSION.VERSION, nextVersion)
            .set(UPLOAD_SESSION.OWNER_ID, session.getOwnerId()).set(UPLOAD_SESSION.PARENT_PATH, parent)
            .set(UPLOAD_SESSION.ENTRY_NAME, session.getName()).set(UPLOAD_SESSION.EXPECTED_BYTES, session.getExpectedBytes())
            .set(UPLOAD_SESSION.EXPECTED_SHA256, session.getExpectedSha256())
            .set(UPLOAD_SESSION.TARGET_OBSERVED_TOKEN, session.getTargetObservedToken())
            .set(UPLOAD_SESSION.NEXT_OFFSET, session.getNextOffset()).set(UPLOAD_SESSION.STAGING_KEY, staging)
            .set(UPLOAD_SESSION.APPEND_LEASE_TOKEN, session.getAppendLeaseToken())
            .set(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT, offset(session.getAppendLeaseExpiresAt()))
            .set(UPLOAD_SESSION.APPEND_OFFSET, session.getAppendOffset()).set(UPLOAD_SESSION.APPEND_LENGTH, session.getAppendLength())
            .set(UPLOAD_SESSION.APPEND_DIGEST, session.getAppendDigest()).set(UPLOAD_SESSION.APPEND_CHUNK_KEY, session.getAppendChunkKey())
            .set(UPLOAD_SESSION.FINALIZING_IDENTITY, session.getFinalizingIdentity())
            .set(UPLOAD_SESSION.FINALIZING_REPLACE, session.getFinalizingReplace())
            .set(UPLOAD_SESSION.FINALIZING_TARGET_IDENTITY, session.getFinalizingTargetIdentity())
            .set(UPLOAD_SESSION.FINALIZING_QUARANTINE_KEY, quarantine)
            .set(UPLOAD_SESSION.FINALIZATION_STATE,
                session.getFinalizationState() == null ? null : session.getFinalizationState().name())
            .set(UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN, session.getFinalizationLeaseToken())
            .set(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT, offset(session.getFinalizationLeaseExpiresAt()))
            .set(UPLOAD_SESSION.EXPIRES_AT, offset(session.getExpiresAt())).set(UPLOAD_SESSION.DELETE_AT, offset(session.getDeleteAt()))
            .set(UPLOAD_SESSION.MAINTENANCE_RETRY_AT, offset(session.getMaintenanceRetryAt()))
            .set(UPLOAD_SESSION.MAINTENANCE_ATTEMPTS, session.getMaintenanceAttempts())
            .set(UPLOAD_SESSION.STATE, session.getState().name()).set(UPLOAD_SESSION.CREATED_AT, offset(session.getCreatedAt()))
            .set(UPLOAD_SESSION.UPDATED_AT, offset(session.getUpdatedAt()))
            .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(session.getId())
                .and(UPLOAD_SESSION.VERSION.eq(session.getVersion()))).execute();
        if (changed != 1) throw new OptimisticLockingFailureException("Upload session changed during save.");
      }
      transaction.deleteFrom(UPLOAD_CHUNK)
          .where(UPLOAD_CHUNK.UPLOAD_SESSION_ID.eq(session.getId())).execute();
      var keys = new HashSet<String>();
      keys.addAll(session.getChunkDigests().keySet());
      keys.addAll(session.getChunkLengths().keySet());
      for (String key : keys) {
        transaction.insertInto(UPLOAD_CHUNK).set(UPLOAD_CHUNK.UPLOAD_SESSION_ID, session.getId())
            .set(UPLOAD_CHUNK.CHUNK_KEY, key).set(UPLOAD_CHUNK.DIGEST, session.getChunkDigests().get(key))
            .set(UPLOAD_CHUNK.CHUNK_LENGTH, session.getChunkLengths().get(key)).execute();
      }
      return find(transaction, session.getId()).orElseThrow();
    });
  }

  @Override public Optional<SharedFolderUploadSession> findById(String id) { return find(database, id); }
  @Override public void deleteById(String id) {
    database.deleteFrom(UPLOAD_SESSION).where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)).execute();
  }

  @Override public long countByOwnerIdAndStateIn(String ownerId,
      Collection<SharedFolderUploadState> states) {
    return database.fetchCount(UPLOAD_SESSION, UPLOAD_SESSION.OWNER_ID.eq(ownerId)
        .and(UPLOAD_SESSION.STATE.in(states.stream().map(Enum::name).toList())));
  }

  @Override public Slice<SharedFolderUploadSession> findByOwnerIdOrderByIdAsc(
      String ownerId, Pageable pageable) {
    return slice(UPLOAD_SESSION.OWNER_ID.eq(ownerId), pageable, UPLOAD_SESSION.UPLOAD_SESSION_ID.asc());
  }

  @Override public Slice<SharedFolderUploadSession> findDueForMaintenance(
      Instant dueAtOrBefore, Pageable pageable) {
    var due = dueAtOrBefore.atOffset(ZoneOffset.UTC);
    Condition active = UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.ACTIVE.name())
        .and(UPLOAD_SESSION.EXPIRES_AT.le(due));
    Condition expired = UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.EXPIRED.name())
        .and(UPLOAD_SESSION.MAINTENANCE_RETRY_AT.isNull()
            .or(UPLOAD_SESSION.MAINTENANCE_RETRY_AT.le(due)));
    return slice(active.or(expired), pageable, UPLOAD_SESSION.MAINTENANCE_RETRY_AT.asc().nullsFirst(),
        UPLOAD_SESSION.EXPIRES_AT.asc(), UPLOAD_SESSION.UPLOAD_SESSION_ID.asc());
  }

  @Override public long expireActive(String id, Instant expiresAtOrBefore, Instant updatedAt) {
    return database.update(UPLOAD_SESSION).set(UPLOAD_SESSION.STATE, SharedFolderUploadState.EXPIRED.name())
        .set(UPLOAD_SESSION.MAINTENANCE_RETRY_AT, offset(updatedAt)).set(UPLOAD_SESSION.MAINTENANCE_ATTEMPTS, 0)
        .set(UPLOAD_SESSION.UPDATED_AT, offset(updatedAt)).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.ACTIVE.name()))
            .and(UPLOAD_SESSION.EXPIRES_AT.le(expiresAtOrBefore.atOffset(ZoneOffset.UTC)))).execute();
  }

  @Override public long deferExpiredMaintenance(String id, int expectedAttempts, Instant retryAt,
      int newAttempts, Instant updatedAt) {
    return database.update(UPLOAD_SESSION).set(UPLOAD_SESSION.MAINTENANCE_RETRY_AT, offset(retryAt))
        .set(UPLOAD_SESSION.MAINTENANCE_ATTEMPTS, newAttempts).set(UPLOAD_SESSION.UPDATED_AT, offset(updatedAt))
        .set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.EXPIRED.name()))
            .and(UPLOAD_SESSION.MAINTENANCE_ATTEMPTS.eq(expectedAttempts))).execute();
  }

  @Override public Optional<Instant> acquireAppendLease(
      String id, String token, long appendOffset, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(UPLOAD_SESSION)
        .set(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT, PostgresqlLeaseFields.expiresAfter(duration))
        .set(UPLOAD_SESSION.APPEND_LEASE_TOKEN, token)
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.APPENDING.name()))
            .and(UPLOAD_SESSION.APPEND_LEASE_TOKEN.isNull())
            .and(UPLOAD_SESSION.APPEND_OFFSET.eq(appendOffset))
            .and(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT.isNull()))
        .returning(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty() : Optional.of(row.getAppendLeaseExpiresAt().toInstant());
  }

  @Override public Optional<Instant> acquireFinalizationLease(String id, String token,
      SharedFolderUploadFinalizationState state, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(UPLOAD_SESSION)
        .set(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN, token)
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.FINALIZING.name()))
            .and(UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN.isNull())
            .and(UPLOAD_SESSION.FINALIZATION_STATE.eq(state.name()))
            .and(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT.isNull()))
        .returning(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getFinalizationLeaseExpiresAt().toInstant());
  }

  @Override public boolean relinquishAppendLease(String id, String token, long appendOffset) {
    var now = DSL.currentOffsetDateTime();
    return database.update(UPLOAD_SESSION).set(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT, now)
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.APPENDING.name()))
            .and(UPLOAD_SESSION.APPEND_LEASE_TOKEN.eq(token))
            .and(UPLOAD_SESSION.APPEND_OFFSET.eq(appendOffset))
            .and(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT.gt(now))).execute() == 1;
  }

  @Override public boolean relinquishFinalizationLease(String id, String token,
      SharedFolderUploadFinalizationState state) {
    var now = DSL.currentOffsetDateTime();
    return database.update(UPLOAD_SESSION).set(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT, now)
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.FINALIZING.name()))
            .and(UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN.eq(token))
            .and(UPLOAD_SESSION.FINALIZATION_STATE.eq(state.name()))
            .and(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT.gt(now))).execute() == 1;
  }

  @Override public Optional<Instant> renewFinalizationLease(String id, String token,
      SharedFolderUploadFinalizationState state, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(UPLOAD_SESSION)
        .set(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.FINALIZING.name()))
            .and(UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN.eq(token))
            .and(UPLOAD_SESSION.FINALIZATION_STATE.eq(state.name()))
            .and(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT.gt(now)))
        .returning(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getFinalizationLeaseExpiresAt().toInstant());
  }

  @Override public Optional<Instant> claimExpiredFinalizationLease(String id, String oldToken,
      SharedFolderUploadFinalizationState state, String newToken, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(UPLOAD_SESSION).set(UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN, newToken)
        .set(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.FINALIZING.name()))
            .and(oldToken == null
                ? UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN.isNull()
                : UPLOAD_SESSION.FINALIZATION_LEASE_TOKEN.eq(oldToken))
            .and(UPLOAD_SESSION.FINALIZATION_STATE.eq(state.name()))
            .and(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT.isNull()
                .or(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT.le(now))))
        .returning(UPLOAD_SESSION.FINALIZATION_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getFinalizationLeaseExpiresAt().toInstant());
  }

  @Override public Optional<Instant> renewAppendLease(String id, String token, long appendOffset,
      Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(UPLOAD_SESSION).set(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.APPENDING.name()))
            .and(UPLOAD_SESSION.APPEND_LEASE_TOKEN.eq(token)).and(UPLOAD_SESSION.APPEND_OFFSET.eq(appendOffset))
            .and(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT.gt(now)))
        .returning(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getAppendLeaseExpiresAt().toInstant());
  }

  @Override public Optional<Instant> claimExpiredAppendLease(String id, String oldToken,
      long appendOffset, String newToken, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(UPLOAD_SESSION).set(UPLOAD_SESSION.APPEND_LEASE_TOKEN, newToken)
        .set(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(UPLOAD_SESSION.UPDATED_AT, now).set(UPLOAD_SESSION.VERSION, UPLOAD_SESSION.VERSION.plus(1L))
        .where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id)
            .and(UPLOAD_SESSION.STATE.eq(SharedFolderUploadState.APPENDING.name()))
            .and(oldToken == null
                ? UPLOAD_SESSION.APPEND_LEASE_TOKEN.isNull()
                : UPLOAD_SESSION.APPEND_LEASE_TOKEN.eq(oldToken))
            .and(UPLOAD_SESSION.APPEND_OFFSET.eq(appendOffset))
            .and(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT.isNull()
                .or(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT.le(now))))
        .returning(UPLOAD_SESSION.APPEND_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getAppendLeaseExpiresAt().toInstant());
  }

  private Slice<SharedFolderUploadSession> slice(
      Condition condition, Pageable pageable, org.jooq.SortField<?>... order) {
    int size = pageable.isPaged() ? pageable.getPageSize() : Integer.MAX_VALUE - 1;
    int offset = pageable.isPaged() ? Math.toIntExact(pageable.getOffset()) : 0;
    List<SharedFolderUploadSession> rows = database.selectFrom(UPLOAD_SESSION).where(condition)
        .orderBy(order).limit(pageable.isPaged() ? size + 1 : size).offset(offset)
        .fetch(row -> map(database, row));
    boolean next = pageable.isPaged() && rows.size() > size;
    return new SliceImpl<>(next ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows), pageable, next);
  }

  private static Optional<SharedFolderUploadSession> find(DSLContext context, String id) {
    return context.selectFrom(UPLOAD_SESSION).where(UPLOAD_SESSION.UPLOAD_SESSION_ID.eq(id))
        .fetchOptional(row -> map(context, row));
  }

  private static SharedFolderUploadSession map(DSLContext context, UploadSessionRecord row) {
    var session = new SharedFolderUploadSession();
    session.setId(row.getUploadSessionId()); session.setVersion(row.getVersion());
    session.setOwnerId(row.getOwnerId()); session.setParentPath(row.getParentPath()); session.setName(row.getEntryName());
    session.setExpectedBytes(row.getExpectedBytes()); session.setExpectedSha256(row.getExpectedSha256());
    session.setTargetObservedToken(row.getTargetObservedToken()); session.setNextOffset(row.getNextOffset());
    var digests = new HashMap<String, String>(); var lengths = new HashMap<String, Long>();
    context.selectFrom(UPLOAD_CHUNK).where(UPLOAD_CHUNK.UPLOAD_SESSION_ID.eq(row.getUploadSessionId()))
        .forEach(chunk -> { if (chunk.getDigest() != null) digests.put(chunk.getChunkKey(), chunk.getDigest());
          if (chunk.getChunkLength() != null) lengths.put(chunk.getChunkKey(), chunk.getChunkLength()); });
    session.setChunkDigests(digests); session.setChunkLengths(lengths); session.setStagingKey(row.getStagingKey());
    session.setAppendLeaseToken(row.getAppendLeaseToken()); session.setAppendLeaseExpiresAt(instant(row.getAppendLeaseExpiresAt()));
    session.setAppendOffset(row.getAppendOffset()); session.setAppendLength(row.getAppendLength());
    session.setAppendDigest(row.getAppendDigest()); session.setAppendChunkKey(row.getAppendChunkKey());
    session.setFinalizingIdentity(row.getFinalizingIdentity()); session.setFinalizingReplace(row.getFinalizingReplace());
    session.setFinalizingTargetIdentity(row.getFinalizingTargetIdentity());
    session.setFinalizingQuarantineKey(row.getFinalizingQuarantineKey());
    session.setFinalizationState(row.getFinalizationState() == null ? null
        : SharedFolderUploadFinalizationState.valueOf(row.getFinalizationState()));
    session.setFinalizationLeaseToken(row.getFinalizationLeaseToken());
    session.setFinalizationLeaseExpiresAt(instant(row.getFinalizationLeaseExpiresAt()));
    session.setExpiresAt(instant(row.getExpiresAt())); session.setDeleteAt(instant(row.getDeleteAt()));
    session.setMaintenanceRetryAt(instant(row.getMaintenanceRetryAt()));
    session.setMaintenanceAttempts(row.getMaintenanceAttempts());
    session.setState(SharedFolderUploadState.valueOf(row.getState()));
    session.setCreatedAt(instant(row.getCreatedAt())); session.setUpdatedAt(instant(row.getUpdatedAt()));
    return session;
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
  private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}

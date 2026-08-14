package dev.christopherbell.sharedfolder.service;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.MUTATION_RECOVERY;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlLeaseFields;
import java.time.Duration;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.persistence.jooq.shared_folder.tables.records.MutationRecoveryRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.OptimisticLockingFailureException;

/** PostgreSQL recovery journal for conditional shared-folder replacements. */
@PostgresPersistence
public class PostgresSharedFolderMutationRecoveryRepository
    implements SharedFolderMutationRecoveryRepository {
  private final DSLContext database;

  public PostgresSharedFolderMutationRecoveryRepository(DSLContext database) {
    this.database = database;
  }

  @Override public SharedFolderMutationRecovery save(SharedFolderMutationRecovery recovery) {
    String source = PostgresqlRelativePath.require(recovery.getSourcePath(), "Recovery source path");
    String parent = PostgresqlRelativePath.requireRootAllowed(
        recovery.getDestinationParentPath(), "Recovery destination parent path");
    String quarantine = recovery.getQuarantineKey() == null ? null
        : PostgresqlRelativePath.require(recovery.getQuarantineKey(), "Recovery quarantine key");
    if (recovery.getVersion() == null) {
      database.insertInto(MUTATION_RECOVERY)
          .set(MUTATION_RECOVERY.MUTATION_RECOVERY_ID, recovery.getId())
          .set(MUTATION_RECOVERY.VERSION, 0L).set(MUTATION_RECOVERY.OWNER_ID, recovery.getOwnerId())
          .set(MUTATION_RECOVERY.SOURCE_PATH, source)
          .set(MUTATION_RECOVERY.DESTINATION_PARENT_PATH, parent)
          .set(MUTATION_RECOVERY.ENTRY_NAME, recovery.getName())
          .set(MUTATION_RECOVERY.SOURCE_IDENTITY, recovery.getSourceIdentity())
          .set(MUTATION_RECOVERY.TARGET_IDENTITY, recovery.getTargetIdentity())
          .set(MUTATION_RECOVERY.QUARANTINE_KEY, quarantine)
          .set(MUTATION_RECOVERY.NATIVE_MODE, recovery.isNativeMode())
          .set(MUTATION_RECOVERY.STATE, recovery.getState().name())
          .set(MUTATION_RECOVERY.OPERATION_LEASE_TOKEN, recovery.getOperationLeaseToken())
          .set(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT, offset(recovery.getOperationLeaseExpiresAt()))
          .set(MUTATION_RECOVERY.CREATED_AT, offset(recovery.getCreatedAt()))
          .set(MUTATION_RECOVERY.UPDATED_AT, offset(recovery.getUpdatedAt())).execute();
    } else {
      long nextVersion = Math.incrementExact(recovery.getVersion());
      int changed = database.update(MUTATION_RECOVERY).set(MUTATION_RECOVERY.VERSION, nextVersion)
          .set(MUTATION_RECOVERY.OWNER_ID, recovery.getOwnerId()).set(MUTATION_RECOVERY.SOURCE_PATH, source)
          .set(MUTATION_RECOVERY.DESTINATION_PARENT_PATH, parent)
          .set(MUTATION_RECOVERY.ENTRY_NAME, recovery.getName())
          .set(MUTATION_RECOVERY.SOURCE_IDENTITY, recovery.getSourceIdentity())
          .set(MUTATION_RECOVERY.TARGET_IDENTITY, recovery.getTargetIdentity())
          .set(MUTATION_RECOVERY.QUARANTINE_KEY, quarantine)
          .set(MUTATION_RECOVERY.NATIVE_MODE, recovery.isNativeMode())
          .set(MUTATION_RECOVERY.STATE, recovery.getState().name())
          .set(MUTATION_RECOVERY.OPERATION_LEASE_TOKEN, recovery.getOperationLeaseToken())
          .set(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT, offset(recovery.getOperationLeaseExpiresAt()))
          .set(MUTATION_RECOVERY.CREATED_AT, offset(recovery.getCreatedAt()))
          .set(MUTATION_RECOVERY.UPDATED_AT, offset(recovery.getUpdatedAt()))
          .where(MUTATION_RECOVERY.MUTATION_RECOVERY_ID.eq(recovery.getId())
              .and(MUTATION_RECOVERY.VERSION.eq(recovery.getVersion()))).execute();
      if (changed != 1) {
        throw new OptimisticLockingFailureException("Shared-folder mutation recovery changed during save.");
      }
    }
    return findById(recovery.getId()).orElseThrow();
  }

  @Override public Optional<SharedFolderMutationRecovery> findById(String id) {
    return database.selectFrom(MUTATION_RECOVERY)
        .where(MUTATION_RECOVERY.MUTATION_RECOVERY_ID.eq(id))
        .fetchOptional(PostgresSharedFolderMutationRecoveryRepository::map);
  }

  @Override public void deleteById(String id) {
    database.deleteFrom(MUTATION_RECOVERY)
        .where(MUTATION_RECOVERY.MUTATION_RECOVERY_ID.eq(id)).execute();
  }

  @Override public List<SharedFolderMutationRecovery> findTop100ByOwnerIdOrderByUpdatedAtAsc(
      String ownerId) {
    return database.selectFrom(MUTATION_RECOVERY).where(MUTATION_RECOVERY.OWNER_ID.eq(ownerId))
        .orderBy(MUTATION_RECOVERY.UPDATED_AT.asc(), MUTATION_RECOVERY.MUTATION_RECOVERY_ID.asc())
        .limit(100).fetch(PostgresSharedFolderMutationRecoveryRepository::map);
  }

  @Override public List<SharedFolderMutationRecovery> findTop100ByOrderByUpdatedAtAsc() {
    return database.selectFrom(MUTATION_RECOVERY)
        .orderBy(MUTATION_RECOVERY.UPDATED_AT.asc(), MUTATION_RECOVERY.MUTATION_RECOVERY_ID.asc())
        .limit(100).fetch(PostgresSharedFolderMutationRecoveryRepository::map);
  }

  @Override public Optional<Instant> acquireOperationLease(String id, String token,
      SharedFolderMutationRecoveryState state, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(MUTATION_RECOVERY)
        .set(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(MUTATION_RECOVERY.OPERATION_LEASE_TOKEN, token)
        .set(MUTATION_RECOVERY.UPDATED_AT, now)
        .where(MUTATION_RECOVERY.MUTATION_RECOVERY_ID.eq(id)
            .and(MUTATION_RECOVERY.OPERATION_LEASE_TOKEN.isNull())
            .and(MUTATION_RECOVERY.STATE.eq(state.name()))
            .and(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT.isNull()))
        .returning(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getOperationLeaseExpiresAt().toInstant());
  }

  @Override public Optional<Instant> renewOperationLease(String id, String token,
      SharedFolderMutationRecoveryState state, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(MUTATION_RECOVERY)
        .set(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(MUTATION_RECOVERY.UPDATED_AT, now)
        .where(MUTATION_RECOVERY.MUTATION_RECOVERY_ID.eq(id)
            .and(MUTATION_RECOVERY.OPERATION_LEASE_TOKEN.eq(token))
            .and(MUTATION_RECOVERY.STATE.eq(state.name()))
            .and(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT.gt(now)))
        .returning(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getOperationLeaseExpiresAt().toInstant());
  }

  @Override public Optional<Instant> claimExpiredOperationLease(String id, String expiredToken,
      SharedFolderMutationRecoveryState state, String recoveryToken, Duration duration) {
    var now = DSL.currentOffsetDateTime();
    var row = database.update(MUTATION_RECOVERY)
        .set(MUTATION_RECOVERY.OPERATION_LEASE_TOKEN, recoveryToken)
        .set(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT,
            PostgresqlLeaseFields.expiresAfter(duration))
        .set(MUTATION_RECOVERY.UPDATED_AT, now)
        .where(MUTATION_RECOVERY.MUTATION_RECOVERY_ID.eq(id)
            .and(expiredToken == null
                ? MUTATION_RECOVERY.OPERATION_LEASE_TOKEN.isNull()
                : MUTATION_RECOVERY.OPERATION_LEASE_TOKEN.eq(expiredToken))
            .and(MUTATION_RECOVERY.STATE.eq(state.name()))
            .and(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT.isNull()
                .or(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT.le(now))))
        .returning(MUTATION_RECOVERY.OPERATION_LEASE_EXPIRES_AT).fetchOne();
    return row == null ? Optional.empty()
        : Optional.of(row.getOperationLeaseExpiresAt().toInstant());
  }

  private static SharedFolderMutationRecovery map(MutationRecoveryRecord row) {
    var recovery = new SharedFolderMutationRecovery();
    recovery.setId(row.getMutationRecoveryId()); recovery.setVersion(row.getVersion());
    recovery.setOwnerId(row.getOwnerId()); recovery.setSourcePath(row.getSourcePath());
    recovery.setDestinationParentPath(row.getDestinationParentPath()); recovery.setName(row.getEntryName());
    recovery.setSourceIdentity(row.getSourceIdentity()); recovery.setTargetIdentity(row.getTargetIdentity());
    recovery.setQuarantineKey(row.getQuarantineKey()); recovery.setNativeMode(row.getNativeMode());
    recovery.setState(SharedFolderMutationRecoveryState.valueOf(row.getState()));
    recovery.setOperationLeaseToken(row.getOperationLeaseToken());
    recovery.setOperationLeaseExpiresAt(instant(row.getOperationLeaseExpiresAt()));
    recovery.setCreatedAt(instant(row.getCreatedAt())); recovery.setUpdatedAt(instant(row.getUpdatedAt()));
    return recovery;
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

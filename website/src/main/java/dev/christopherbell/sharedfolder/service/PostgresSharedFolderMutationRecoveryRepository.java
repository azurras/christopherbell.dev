package dev.christopherbell.sharedfolder.service;

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
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL recovery journal for conditional shared-folder replacements. */
@PostgresPersistence
public class PostgresSharedFolderMutationRecoveryRepository
    implements SharedFolderMutationRecoveryRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresSharedFolderMutationRecoveryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("shared_folder", "mutation_recovery");
  }

  @Override
  public SharedFolderMutationRecovery save(SharedFolderMutationRecovery recovery) {
    String source = PostgresqlRelativePath.require(recovery.getSourcePath(), "Recovery source path");
    String parent = PostgresqlRelativePath.requireRootAllowed(
        recovery.getDestinationParentPath(), "Recovery destination parent path");
    String quarantine = recovery.getQuarantineKey() == null ? null
        : PostgresqlRelativePath.require(recovery.getQuarantineKey(), "Recovery quarantine key");
    var parameters = parameters(recovery, source, parent, quarantine);
    if (recovery.getVersion() == null) {
      database.sql("""
              insert into %s (
                mutation_recovery_id, version, owner_id, source_path,
                destination_parent_path, entry_name, source_identity, target_identity,
                quarantine_key, native_mode, state, operation_lease_token,
                operation_lease_expires_at, created_at, updated_at)
              values (
                :id, 0, :ownerId, :sourcePath, :parentPath, :entryName,
                :sourceIdentity, :targetIdentity, :quarantineKey, :nativeMode, :state,
                :leaseToken, :leaseExpiresAt, :createdAt, :updatedAt)
              """.formatted(table)).paramSource(parameters).update();
    } else {
      int changed = database.sql("""
              update %s set version = :nextVersion, owner_id = :ownerId,
                source_path = :sourcePath, destination_parent_path = :parentPath,
                entry_name = :entryName, source_identity = :sourceIdentity,
                target_identity = :targetIdentity, quarantine_key = :quarantineKey,
                native_mode = :nativeMode, state = :state, operation_lease_token = :leaseToken,
                operation_lease_expires_at = :leaseExpiresAt,
                created_at = :createdAt, updated_at = :updatedAt
              where mutation_recovery_id = :id and version = :expectedVersion
              """.formatted(table)).paramSource(parameters
                  .addValue("nextVersion", Math.incrementExact(recovery.getVersion()))
                  .addValue("expectedVersion", recovery.getVersion())).update();
      if (changed != 1) {
        throw new OptimisticLockingFailureException(
            "Shared-folder mutation recovery changed during save.");
      }
    }
    return findById(recovery.getId()).orElseThrow();
  }

  @Override
  public Optional<SharedFolderMutationRecovery> findById(String id) {
    return database.sql("select * from %s where mutation_recovery_id = :id".formatted(table))
        .param("id", id).query(PostgresSharedFolderMutationRecoveryRepository::map).optional();
  }

  @Override
  public void deleteById(String id) {
    database.sql("delete from %s where mutation_recovery_id = :id".formatted(table))
        .param("id", id).update();
  }

  @Override
  public List<SharedFolderMutationRecovery> findTop100ByOwnerIdOrderByUpdatedAtAsc(String ownerId) {
    return database.sql("""
            select * from %s where owner_id = :ownerId
            order by updated_at asc, mutation_recovery_id asc limit 100
            """.formatted(table)).param("ownerId", ownerId)
        .query(PostgresSharedFolderMutationRecoveryRepository::map).list();
  }

  @Override
  public List<SharedFolderMutationRecovery> findTop100ByOrderByUpdatedAtAsc() {
    return database.sql("""
            select * from %s order by updated_at asc, mutation_recovery_id asc limit 100
            """.formatted(table)).query(PostgresSharedFolderMutationRecoveryRepository::map).list();
  }

  @Override
  public Optional<Instant> acquireOperationLease(
      String id, String token, SharedFolderMutationRecoveryState state, Duration duration) {
    return lease("""
        operation_lease_token is null and operation_lease_expires_at is null
        """, id, null, token, state, duration);
  }

  @Override
  public Optional<Instant> renewOperationLease(
      String id, String token, SharedFolderMutationRecoveryState state, Duration duration) {
    return lease("""
        operation_lease_token = :expectedToken and operation_lease_expires_at > current_timestamp
        """, id, token, token, state, duration);
  }

  @Override
  public Optional<Instant> claimExpiredOperationLease(
      String id, String expiredToken, SharedFolderMutationRecoveryState state,
      String recoveryToken, Duration duration) {
    String tokenPredicate = expiredToken == null
        ? "operation_lease_token is null" : "operation_lease_token = :expectedToken";
    return lease(tokenPredicate + " and (operation_lease_expires_at is null "
        + "or operation_lease_expires_at <= current_timestamp)",
        id, expiredToken, recoveryToken, state, duration);
  }

  private Optional<Instant> lease(
      String predicate, String id, String expectedToken, String token,
      SharedFolderMutationRecoveryState state, Duration duration) {
    var statement = database.sql("""
            update %s set
              operation_lease_expires_at = current_timestamp + (:micros * interval '1 microsecond'),
              operation_lease_token = :token, updated_at = current_timestamp
            where mutation_recovery_id = :id and state = :state and %s
            returning operation_lease_expires_at
            """.formatted(table, predicate))
        .param("micros", PostgresqlLeaseFields.microseconds(duration))
        .param("token", token).param("id", id).param("state", state.name());
    if (expectedToken != null) statement.param("expectedToken", expectedToken);
    return statement.query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
  }

  private static MapSqlParameterSource parameters(
      SharedFolderMutationRecovery recovery, String source, String parent, String quarantine) {
    return new MapSqlParameterSource()
        .addValue("id", recovery.getId()).addValue("ownerId", recovery.getOwnerId())
        .addValue("sourcePath", source).addValue("parentPath", parent)
        .addValue("entryName", recovery.getName())
        .addValue("sourceIdentity", recovery.getSourceIdentity(), Types.VARCHAR)
        .addValue("targetIdentity", recovery.getTargetIdentity(), Types.VARCHAR)
        .addValue("quarantineKey", quarantine, Types.VARCHAR)
        .addValue("nativeMode", recovery.isNativeMode()).addValue("state", recovery.getState().name())
        .addValue("leaseToken", recovery.getOperationLeaseToken(), Types.VARCHAR)
        .addValue("leaseExpiresAt", offset(recovery.getOperationLeaseExpiresAt()),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("createdAt", offset(recovery.getCreatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("updatedAt", offset(recovery.getUpdatedAt()), Types.TIMESTAMP_WITH_TIMEZONE);
  }

  private static SharedFolderMutationRecovery map(ResultSet row, int rowNumber) throws SQLException {
    var recovery = new SharedFolderMutationRecovery();
    recovery.setId(row.getString("mutation_recovery_id"));
    recovery.setVersion(row.getLong("version"));
    recovery.setOwnerId(row.getString("owner_id"));
    recovery.setSourcePath(row.getString("source_path"));
    recovery.setDestinationParentPath(row.getString("destination_parent_path"));
    recovery.setName(row.getString("entry_name"));
    recovery.setSourceIdentity(row.getString("source_identity"));
    recovery.setTargetIdentity(row.getString("target_identity"));
    recovery.setQuarantineKey(row.getString("quarantine_key"));
    recovery.setNativeMode(row.getBoolean("native_mode"));
    recovery.setState(SharedFolderMutationRecoveryState.valueOf(row.getString("state")));
    recovery.setOperationLeaseToken(row.getString("operation_lease_token"));
    recovery.setOperationLeaseExpiresAt(instant(row, "operation_lease_expires_at"));
    recovery.setCreatedAt(instant(row, "created_at"));
    recovery.setUpdatedAt(instant(row, "updated_at"));
    return recovery;
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}

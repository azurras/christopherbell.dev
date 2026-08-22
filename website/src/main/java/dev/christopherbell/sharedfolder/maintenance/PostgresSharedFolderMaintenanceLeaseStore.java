package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlLeaseFields;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseIdentity;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL database-time implementation of the fixed shared-folder maintenance lease. */
@PostgresPersistence
public class PostgresSharedFolderMaintenanceLeaseStore
    implements SharedFolderMaintenanceLeaseStore {
  private static final String LEASE_NAME = SharedFolderMaintenanceLeaseDocument.ID;
  private final JdbcClient database;
  private final String table;

  public PostgresSharedFolderMaintenanceLeaseStore(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("shared_folder", "maintenance_lease");
  }

  @Override public Optional<LeaseGrant> tryAcquire(String ownerToken, Duration duration) {
    new LeaseIdentity(LEASE_NAME, ownerToken);
    return database.sql("""
            insert into %s
              (lease_name, owner_token, fence_token, acquired_at, expires_at)
            values (:leaseName, :owner, 1, current_timestamp,
                    current_timestamp + (:microseconds * interval '1 microsecond'))
            on conflict (lease_name) do update set
              owner_token = excluded.owner_token,
              fence_token = coalesce(%s.fence_token, 0) + 1,
              acquired_at = current_timestamp,
              expires_at = current_timestamp + (:microseconds * interval '1 microsecond')
            where %s.owner_token = :owner or %s.expires_at <= current_timestamp
            returning *
            """.formatted(table, table, table, table))
        .param("leaseName", LEASE_NAME).param("owner", ownerToken)
        .param("microseconds", PostgresqlLeaseFields.microseconds(duration))
        .query(PostgresSharedFolderMaintenanceLeaseStore::map).optional();
  }

  @Override public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
    if (!LEASE_NAME.equals(grant.leaseName())) {
      return Optional.empty();
    }
    return database.sql("""
            update %s
            set expires_at = current_timestamp + (:microseconds * interval '1 microsecond')
            where lease_name = :leaseName and owner_token = :owner
              and fence_token = :fence and expires_at > current_timestamp
            returning *
            """.formatted(table))
        .param("microseconds", PostgresqlLeaseFields.microseconds(duration))
        .param("leaseName", LEASE_NAME).param("owner", grant.ownerId())
        .param("fence", grant.fenceToken())
        .query(PostgresSharedFolderMaintenanceLeaseStore::map).optional();
  }

  @Override public boolean release(LeaseGrant grant) {
    if (!LEASE_NAME.equals(grant.leaseName())) {
      return false;
    }
    return database.sql("""
            update %s set owner_token = 'released', expires_at = :epoch
            where lease_name = :leaseName and owner_token = :owner
              and fence_token = :fence and expires_at > current_timestamp
            """.formatted(table))
        .param("epoch", Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .param("leaseName", LEASE_NAME).param("owner", grant.ownerId())
        .param("fence", grant.fenceToken()).update() == 1;
  }

  private static LeaseGrant map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    return new LeaseGrant(row.getString("lease_name"), row.getString("owner_token"),
        row.getLong("fence_token"),
        row.getObject("expires_at", OffsetDateTime.class).toInstant());
  }

}

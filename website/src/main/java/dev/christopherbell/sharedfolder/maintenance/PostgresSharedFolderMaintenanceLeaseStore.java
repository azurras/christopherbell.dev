package dev.christopherbell.sharedfolder.maintenance;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.MAINTENANCE_LEASE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlLeaseFields;
import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseIdentity;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** PostgreSQL database-time implementation of the fixed shared-folder maintenance lease. */
@PostgresPersistence
public class PostgresSharedFolderMaintenanceLeaseStore
    implements SharedFolderMaintenanceLeaseStore {
  private static final String LEASE_NAME = SharedFolderMaintenanceLeaseDocument.ID;
  private final DSLContext database;

  public PostgresSharedFolderMaintenanceLeaseStore(DSLContext database) {
    this.database = database;
  }

  @Override public Optional<LeaseGrant> tryAcquire(String ownerToken, Duration duration) {
    new LeaseIdentity(LEASE_NAME, ownerToken);
    Field<OffsetDateTime> now = DSL.currentOffsetDateTime();
    Field<OffsetDateTime> expiry = PostgresqlLeaseFields.expiresAfter(duration);
    var row = database.insertInto(MAINTENANCE_LEASE).set(MAINTENANCE_LEASE.LEASE_NAME, LEASE_NAME)
        .set(MAINTENANCE_LEASE.OWNER_TOKEN, ownerToken).set(MAINTENANCE_LEASE.FENCE_TOKEN, 1L)
        .set(MAINTENANCE_LEASE.ACQUIRED_AT, now).set(MAINTENANCE_LEASE.EXPIRES_AT, expiry)
        .onConflict(MAINTENANCE_LEASE.LEASE_NAME).doUpdate()
        .set(MAINTENANCE_LEASE.OWNER_TOKEN, ownerToken)
        .set(MAINTENANCE_LEASE.FENCE_TOKEN,
            DSL.coalesce(MAINTENANCE_LEASE.FENCE_TOKEN, 0L).plus(1L))
        .set(MAINTENANCE_LEASE.ACQUIRED_AT, now).set(MAINTENANCE_LEASE.EXPIRES_AT, expiry)
        .where(MAINTENANCE_LEASE.OWNER_TOKEN.eq(ownerToken)
            .or(MAINTENANCE_LEASE.EXPIRES_AT.le(now))).returning().fetchOne();
    return row == null ? Optional.empty() : Optional.of(map(row));
  }

  @Override public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
    if (!LEASE_NAME.equals(grant.leaseName())) {
      return Optional.empty();
    }
    Field<OffsetDateTime> now = DSL.currentOffsetDateTime();
    var row = database.update(MAINTENANCE_LEASE)
        .set(MAINTENANCE_LEASE.EXPIRES_AT, PostgresqlLeaseFields.expiresAfter(duration))
        .where(MAINTENANCE_LEASE.LEASE_NAME.eq(LEASE_NAME)
            .and(MAINTENANCE_LEASE.OWNER_TOKEN.eq(grant.ownerId()))
            .and(MAINTENANCE_LEASE.FENCE_TOKEN.eq(grant.fenceToken()))
            .and(MAINTENANCE_LEASE.EXPIRES_AT.gt(now))).returning().fetchOne();
    return row == null ? Optional.empty() : Optional.of(map(row));
  }

  @Override public boolean release(LeaseGrant grant) {
    if (!LEASE_NAME.equals(grant.leaseName())) {
      return false;
    }
    Field<OffsetDateTime> now = DSL.currentOffsetDateTime();
    return database.update(MAINTENANCE_LEASE).set(MAINTENANCE_LEASE.OWNER_TOKEN, "released")
        .set(MAINTENANCE_LEASE.EXPIRES_AT, Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .where(MAINTENANCE_LEASE.LEASE_NAME.eq(LEASE_NAME)
            .and(MAINTENANCE_LEASE.OWNER_TOKEN.eq(grant.ownerId()))
            .and(MAINTENANCE_LEASE.FENCE_TOKEN.eq(grant.fenceToken()))
            .and(MAINTENANCE_LEASE.EXPIRES_AT.gt(now))).execute() == 1;
  }

  private static LeaseGrant map(
      dev.christopherbell.persistence.jooq.shared_folder.tables.records.MaintenanceLeaseRecord row) {
    return new LeaseGrant(row.getLeaseName(), row.getOwnerToken(), row.getFenceToken(),
        row.getExpiresAt().toInstant());
  }

}

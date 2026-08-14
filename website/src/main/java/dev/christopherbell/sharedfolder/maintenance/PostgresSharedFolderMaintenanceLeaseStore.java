package dev.christopherbell.sharedfolder.maintenance;

import static dev.christopherbell.persistence.jooq.shared_folder.Tables.MAINTENANCE_LEASE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

  @Override public boolean tryAcquire(String ownerToken, Instant acquiredAt, Instant expiresAt) {
    Field<OffsetDateTime> now = DSL.currentOffsetDateTime();
    Field<OffsetDateTime> expiry = databaseExpiry(acquiredAt, expiresAt);
    return database.insertInto(MAINTENANCE_LEASE).set(MAINTENANCE_LEASE.LEASE_NAME, LEASE_NAME)
        .set(MAINTENANCE_LEASE.OWNER_TOKEN, ownerToken).set(MAINTENANCE_LEASE.FENCE_TOKEN, 1L)
        .set(MAINTENANCE_LEASE.ACQUIRED_AT, now).set(MAINTENANCE_LEASE.EXPIRES_AT, expiry)
        .onConflict(MAINTENANCE_LEASE.LEASE_NAME).doUpdate()
        .set(MAINTENANCE_LEASE.OWNER_TOKEN, ownerToken)
        .set(MAINTENANCE_LEASE.FENCE_TOKEN, MAINTENANCE_LEASE.FENCE_TOKEN.plus(1L))
        .set(MAINTENANCE_LEASE.ACQUIRED_AT, now).set(MAINTENANCE_LEASE.EXPIRES_AT, expiry)
        .where(MAINTENANCE_LEASE.OWNER_TOKEN.eq(ownerToken)
            .or(MAINTENANCE_LEASE.EXPIRES_AT.le(now))).execute() == 1;
  }

  @Override public boolean renew(String ownerToken, Instant renewedAt, Instant expiresAt) {
    Field<OffsetDateTime> now = DSL.currentOffsetDateTime();
    return database.update(MAINTENANCE_LEASE)
        .set(MAINTENANCE_LEASE.EXPIRES_AT, databaseExpiry(renewedAt, expiresAt))
        .where(MAINTENANCE_LEASE.LEASE_NAME.eq(LEASE_NAME)
            .and(MAINTENANCE_LEASE.OWNER_TOKEN.eq(ownerToken))
            .and(MAINTENANCE_LEASE.EXPIRES_AT.gt(now))).execute() == 1;
  }

  @Override public boolean release(String ownerToken) {
    return database.update(MAINTENANCE_LEASE).set(MAINTENANCE_LEASE.OWNER_TOKEN, "released")
        .set(MAINTENANCE_LEASE.EXPIRES_AT, Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .where(MAINTENANCE_LEASE.LEASE_NAME.eq(LEASE_NAME)
            .and(MAINTENANCE_LEASE.OWNER_TOKEN.eq(ownerToken))).execute() == 1;
  }

  private static Field<OffsetDateTime> databaseExpiry(Instant start, Instant end) {
    long microseconds;
    try {
      Duration duration = Duration.between(start, end);
      if (duration.isZero() || duration.isNegative()) {
        throw new IllegalArgumentException("Maintenance lease duration must be positive.");
      }
      microseconds = Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L),
          duration.getNano() / 1_000L);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Maintenance lease duration is too large.", overflow);
    }
    return DSL.field("current_timestamp + ({0} * interval '1 microsecond')",
        OffsetDateTime.class, DSL.val(microseconds));
  }
}

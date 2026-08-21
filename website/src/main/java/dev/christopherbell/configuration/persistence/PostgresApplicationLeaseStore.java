package dev.christopherbell.configuration.persistence;

import static dev.christopherbell.persistence.jooq.platform.Tables.APPLICATION_LEASE;

import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseIdentity;
import dev.christopherbell.libs.lease.LeaseStore;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** PostgreSQL database-time lease adapter with monotonic fencing. */
@PostgresPersistence
public class PostgresApplicationLeaseStore implements LeaseStore {
  private final DSLContext database;

  public PostgresApplicationLeaseStore(DSLContext database) { this.database = database; }

  @Override public Optional<LeaseGrant> tryAcquire(
      String leaseName, String ownerId, Duration duration) {
    new LeaseIdentity(leaseName, ownerId);
    Field<OffsetDateTime> now = DSL.currentOffsetDateTime();
    Field<OffsetDateTime> expiresAt = expiry(duration);
    var row = database.insertInto(APPLICATION_LEASE)
        .set(APPLICATION_LEASE.LEASE_NAME, leaseName).set(APPLICATION_LEASE.OWNER_TOKEN, ownerId)
        .set(APPLICATION_LEASE.FENCE_TOKEN, 1L).set(APPLICATION_LEASE.ACQUIRED_AT, now)
        .set(APPLICATION_LEASE.EXPIRES_AT, expiresAt)
        .onConflict(APPLICATION_LEASE.LEASE_NAME).doUpdate()
        .set(APPLICATION_LEASE.OWNER_TOKEN, ownerId)
        .set(APPLICATION_LEASE.FENCE_TOKEN,
            DSL.coalesce(APPLICATION_LEASE.FENCE_TOKEN, 0L).plus(1L))
        .set(APPLICATION_LEASE.ACQUIRED_AT, now).set(APPLICATION_LEASE.EXPIRES_AT, expiresAt)
        .where(APPLICATION_LEASE.OWNER_TOKEN.eq(ownerId)
            .or(APPLICATION_LEASE.EXPIRES_AT.le(now))).returning().fetchOne();
    return row == null ? Optional.empty() : Optional.of(map(row));
  }

  @Override public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
    Field<OffsetDateTime> now = DSL.currentOffsetDateTime();
    var row = database.update(APPLICATION_LEASE).set(APPLICATION_LEASE.EXPIRES_AT, expiry(duration))
        .where(APPLICATION_LEASE.LEASE_NAME.eq(grant.leaseName())
            .and(APPLICATION_LEASE.OWNER_TOKEN.eq(grant.ownerId()))
            .and(APPLICATION_LEASE.FENCE_TOKEN.eq(grant.fenceToken()))
            .and(APPLICATION_LEASE.EXPIRES_AT.gt(now))).returning().fetchOne();
    return row == null ? Optional.empty() : Optional.of(map(row));
  }

  @Override public boolean release(LeaseGrant grant) {
    return database.update(APPLICATION_LEASE).set(APPLICATION_LEASE.OWNER_TOKEN, "released")
        .set(APPLICATION_LEASE.EXPIRES_AT, Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .where(APPLICATION_LEASE.LEASE_NAME.eq(grant.leaseName())
            .and(APPLICATION_LEASE.OWNER_TOKEN.eq(grant.ownerId()))
            .and(APPLICATION_LEASE.FENCE_TOKEN.eq(grant.fenceToken()))).execute() == 1;
  }

  private static LeaseGrant map(
      dev.christopherbell.persistence.jooq.platform.tables.records.ApplicationLeaseRecord row) {
    return new LeaseGrant(row.getLeaseName(), row.getOwnerToken(), row.getFenceToken(),
        row.getExpiresAt().toInstant());
  }

  private static Field<OffsetDateTime> expiry(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Lease duration must be positive.");
    }
    long microseconds;
    try {
      microseconds = Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L),
          duration.getNano() / 1_000L);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Lease duration is too large.", overflow);
    }
    return DSL.field("current_timestamp + ({0} * interval '1 microsecond')",
        OffsetDateTime.class, DSL.val(microseconds));
  }
}

package dev.christopherbell.configuration.persistence;

import dev.christopherbell.libs.lease.LeaseGrant;
import dev.christopherbell.libs.lease.LeaseIdentity;
import dev.christopherbell.libs.lease.LeaseStore;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL database-time lease adapter with monotonic fencing. */
@PostgresPersistence
public class PostgresApplicationLeaseStore implements LeaseStore {
  private final JdbcClient database;
  private final String table;

  public PostgresApplicationLeaseStore(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("platform", "application_lease");
  }

  @Override public Optional<LeaseGrant> tryAcquire(
      String leaseName, String ownerId, Duration duration) {
    new LeaseIdentity(leaseName, ownerId);
    return database.sql("""
            insert into %s
              (lease_name, owner_token, fence_token, acquired_at, expires_at)
            values
              (:leaseName, :ownerId, 1, current_timestamp,
                current_timestamp + (:durationMicros * interval '1 microsecond'))
            on conflict (lease_name) do update set
              owner_token = excluded.owner_token,
              fence_token = coalesce(%s.fence_token, 0) + 1,
              acquired_at = current_timestamp,
              expires_at = current_timestamp + (:durationMicros * interval '1 microsecond')
            where %s.owner_token = :ownerId or %s.expires_at <= current_timestamp
            returning lease_name, owner_token, fence_token, expires_at
            """.formatted(table, table, table, table))
        .param("leaseName", leaseName)
        .param("ownerId", ownerId)
        .param("durationMicros", durationMicroseconds(duration))
        .query(PostgresApplicationLeaseStore::map)
        .optional();
  }

  @Override public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
    return database.sql("""
            update %s set
              expires_at = current_timestamp + (:durationMicros * interval '1 microsecond')
            where lease_name = :leaseName
              and owner_token = :ownerId
              and fence_token = :fenceToken
              and expires_at > current_timestamp
            returning lease_name, owner_token, fence_token, expires_at
            """.formatted(table))
        .param("durationMicros", durationMicroseconds(duration))
        .param("leaseName", grant.leaseName())
        .param("ownerId", grant.ownerId())
        .param("fenceToken", grant.fenceToken())
        .query(PostgresApplicationLeaseStore::map)
        .optional();
  }

  @Override public boolean release(LeaseGrant grant) {
    return database.sql("""
            update %s set owner_token = 'released', expires_at = :epoch
            where lease_name = :leaseName
              and owner_token = :ownerId
              and fence_token = :fenceToken
            """.formatted(table))
        .param("epoch", Instant.EPOCH.atOffset(ZoneOffset.UTC))
        .param("leaseName", grant.leaseName())
        .param("ownerId", grant.ownerId())
        .param("fenceToken", grant.fenceToken())
        .update() == 1;
  }

  private static LeaseGrant map(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
    return new LeaseGrant(
        row.getString("lease_name"),
        row.getString("owner_token"),
        row.getLong("fence_token"),
        row.getObject("expires_at", OffsetDateTime.class).toInstant());
  }

  private static long durationMicroseconds(Duration duration) {
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
    return microseconds;
  }
}

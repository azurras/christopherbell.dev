package dev.christopherbell.configuration.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** PostgreSQL server-time expressions shared by fenced lease adapters. */
@PostgresPersistenceSupport
public final class PostgresqlLeaseFields {
  private PostgresqlLeaseFields() {}

  public static Field<OffsetDateTime> expiresAfter(Duration duration) {
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

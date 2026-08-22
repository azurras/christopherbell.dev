package dev.christopherbell.configuration.persistence;

import java.time.Duration;

/** PostgreSQL server-time expressions shared by fenced lease adapters. */
@PostgresPersistenceSupport
public final class PostgresqlLeaseFields {
  private PostgresqlLeaseFields() {}

  public static long microseconds(Duration duration) {
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

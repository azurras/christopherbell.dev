package dev.christopherbell.libs.lease;

import java.time.Duration;
import java.util.Optional;

/** Atomic database-time lease persistence supplied by the consuming application. */
public interface LeaseStore {
  Optional<LeaseGrant> tryAcquire(String leaseName, String ownerId, Duration duration);
  Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration);
  boolean release(LeaseGrant grant);
}

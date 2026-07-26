package dev.christopherbell.configuration.mongo.lease;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Owner-scoped lease guard that renews at half of the configured lease duration. */
public final class RenewingMongoLease implements CollectorLeaseGuard {
  private final MongoLeaseService leases;
  private final Clock clock;
  private final String leaseName;
  private final String ownerToken;
  private final Duration duration;
  private final Duration renewalInterval;
  private Instant nextRenewal;

  public RenewingMongoLease(
      MongoLeaseService leases,
      Clock clock,
      String leaseName,
      String ownerToken,
      Duration duration,
      Instant acquiredOn
  ) {
    this.leases = leases;
    this.clock = clock;
    this.leaseName = leaseName;
    this.ownerToken = ownerToken;
    this.duration = duration;
    this.renewalInterval = duration.dividedBy(2);
    this.nextRenewal = acquiredOn.plus(renewalInterval);
  }

  @Override
  public synchronized void verifyHeld() {
    var now = clock.instant();
    if (now.isBefore(nextRenewal)) {
      return;
    }
    if (!leases.renew(leaseName, ownerToken, now, now.plus(duration))) {
      throw new LeaseOwnershipLostException(leaseName);
    }
    nextRenewal = now.plus(renewalInterval);
  }
}

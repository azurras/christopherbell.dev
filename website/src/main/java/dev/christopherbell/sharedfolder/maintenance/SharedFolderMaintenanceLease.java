package dev.christopherbell.sharedfolder.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import dev.christopherbell.libs.lease.LeaseGrant;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns one unique process token for the fixed durable maintenance lease. */
@Component
public final class SharedFolderMaintenanceLease {
  private static final Duration DEFAULT_DURATION = Duration.ofMinutes(30);

  private final SharedFolderMaintenanceLeaseStore store;
  private final Duration duration;
  private final String ownerToken;
  private LeaseGrant grant;

  @Autowired
  public SharedFolderMaintenanceLease(SharedFolderMaintenanceLeaseStore store, Clock clock) {
    this(store, clock, DEFAULT_DURATION, () -> UUID.randomUUID().toString());
  }

  public SharedFolderMaintenanceLease(
      SharedFolderMaintenanceLeaseStore store,
      Clock clock,
      Duration duration,
      Supplier<String> ownerTokens) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Maintenance lease duration must be positive");
    }
    String owner = ownerTokens == null ? null : ownerTokens.get();
    if (owner == null || owner.isBlank() || owner.length() > 128) {
      throw new IllegalArgumentException("Maintenance lease owner is invalid");
    }
    this.store = Objects.requireNonNull(store, "store");
    Objects.requireNonNull(clock, "clock");
    this.duration = duration;
    this.ownerToken = owner;
  }

  public synchronized boolean acquire() {
    var acquired = store.tryAcquire(ownerToken, duration);
    acquired.ifPresent(value -> grant = value);
    return acquired.isPresent();
  }

  public synchronized boolean renew() {
    if (grant == null) {
      return false;
    }
    var renewed = store.renew(grant, duration);
    renewed.ifPresent(value -> grant = value);
    return renewed.isPresent();
  }

  public synchronized boolean release() {
    if (grant == null || !store.release(grant)) {
      return false;
    }
    grant = null;
    return true;
  }
}

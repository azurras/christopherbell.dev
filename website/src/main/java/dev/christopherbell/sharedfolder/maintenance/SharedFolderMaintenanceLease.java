package dev.christopherbell.sharedfolder.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Owns one unique process token for the fixed durable maintenance lease. */
@Component
public final class SharedFolderMaintenanceLease {
  private static final Duration DEFAULT_DURATION = Duration.ofMinutes(30);

  private final SharedFolderMaintenanceLeaseStore store;
  private final Clock clock;
  private final Duration duration;
  private final String ownerToken;

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
    this.store = store;
    this.clock = clock;
    this.duration = duration;
    this.ownerToken = owner;
  }

  public boolean acquire() {
    Instant now = clock.instant();
    return store.tryAcquire(ownerToken, now, now.plus(duration));
  }

  public boolean renew() {
    Instant now = clock.instant();
    return store.renew(ownerToken, now, now.plus(duration));
  }

  public boolean release() {
    return store.release(ownerToken);
  }
}

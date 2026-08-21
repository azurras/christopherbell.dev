package dev.christopherbell.libs.lease;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Owns current lease grants while preserving transition callers' boolean API. */
@Service
public class LeaseService {
  private final LeaseStore store;
  private final ConcurrentHashMap<LeaseIdentity, LeaseGrant> grants = new ConcurrentHashMap<>();

  public LeaseService(LeaseStore store) { this.store = store; }

  public Optional<LeaseGrant> tryAcquire(String name, String ownerId, Duration duration) {
    Optional<LeaseGrant> acquired = store.tryAcquire(name, ownerId, requireDuration(duration));
    acquired.ifPresent(grant -> grants.put(new LeaseIdentity(name, ownerId), grant));
    return acquired;
  }

  public Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
    Optional<LeaseGrant> renewed = store.renew(grant, requireDuration(duration));
    renewed.ifPresent(value -> grants.put(new LeaseIdentity(value.leaseName(), value.ownerId()), value));
    return renewed;
  }

  public boolean release(LeaseGrant grant) {
    boolean released = store.release(grant);
    if (released) grants.remove(new LeaseIdentity(grant.leaseName(), grant.ownerId()), grant);
    return released;
  }

  public boolean tryAcquire(String name, String ownerId, Instant now, Instant expiresAt) {
    return tryAcquire(name, ownerId, duration(now, expiresAt)).isPresent();
  }

  public boolean renew(String name, String ownerId, Instant now, Instant expiresAt) {
    LeaseGrant grant = grants.get(new LeaseIdentity(name, ownerId));
    return grant != null && renew(grant, duration(now, expiresAt)).isPresent();
  }

  public boolean release(String name, String ownerId) {
    LeaseGrant grant = grants.get(new LeaseIdentity(name, ownerId));
    return grant != null && release(grant);
  }

  private static Duration duration(Instant start, Instant end) {
    if (start == null || end == null) throw new IllegalArgumentException("Lease deadline is required.");
    return requireDuration(Duration.between(start, end));
  }

  private static Duration requireDuration(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Lease duration must be positive.");
    }
    return duration;
  }

  private record LeaseIdentity(String leaseName, String ownerId) {}
}

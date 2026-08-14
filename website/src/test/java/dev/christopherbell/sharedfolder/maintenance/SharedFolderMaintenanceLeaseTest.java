package dev.christopherbell.sharedfolder.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import dev.christopherbell.libs.lease.LeaseGrant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SharedFolderMaintenanceLeaseTest {

  @Test
  void onlyTheOwnerCanReleaseAndReleasePermitsItsPeer() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T12:00:00Z"));
    InMemoryLeaseStore store = new InMemoryLeaseStore(clock);
    SharedFolderMaintenanceLease first = lease(store, clock, "owner-a");
    SharedFolderMaintenanceLease peer = lease(store, clock, "owner-b");

    assertThat(first.acquire()).isTrue();
    assertThat(peer.acquire()).isFalse();
    assertThat(peer.renew()).isFalse();
    assertThat(peer.release()).isFalse();
    assertThat(first.release()).isTrue();
    assertThat(peer.acquire()).isTrue();
  }

  @Test
  void peerReclaimsAnExpiredCrashLease() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T12:00:00Z"));
    InMemoryLeaseStore store = new InMemoryLeaseStore(clock);
    SharedFolderMaintenanceLease crashed = lease(store, clock, "crashed-owner");
    SharedFolderMaintenanceLease peer = lease(store, clock, "peer-owner");

    assertThat(crashed.acquire()).isTrue();
    clock.advance(Duration.ofMinutes(31));

    assertThat(peer.acquire()).isTrue();
    assertThat(crashed.release()).isFalse();
  }

  private SharedFolderMaintenanceLease lease(
      SharedFolderMaintenanceLeaseStore store, Clock clock, String owner) {
    return new SharedFolderMaintenanceLease(
        store, clock, Duration.ofMinutes(30), () -> owner);
  }

  private static final class InMemoryLeaseStore implements SharedFolderMaintenanceLeaseStore {
    private String owner;
    private Instant expiresAt = Instant.EPOCH;
    private long fence;
    private final Clock clock;

    private InMemoryLeaseStore(Clock clock) { this.clock = clock; }

    @Override
    public synchronized Optional<LeaseGrant> tryAcquire(String ownerToken, Duration duration) {
      Instant now = clock.instant();
      if (owner != null && !owner.equals(ownerToken) && expiresAt.isAfter(now)) return Optional.empty();
      owner = ownerToken;
      expiresAt = now.plus(duration);
      return Optional.of(new LeaseGrant(SharedFolderMaintenanceLeaseDocument.ID,
          owner, ++fence, expiresAt));
    }

    @Override
    public synchronized Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration) {
      if (!grant.ownerId().equals(owner) || grant.fenceToken() != fence
          || !expiresAt.isAfter(clock.instant())) return Optional.empty();
      expiresAt = clock.instant().plus(duration);
      return Optional.of(new LeaseGrant(grant.leaseName(), owner, fence, expiresAt));
    }

    @Override
    public synchronized boolean release(LeaseGrant grant) {
      if (!grant.ownerId().equals(owner) || grant.fenceToken() != fence
          || !expiresAt.isAfter(clock.instant())) return false;
      owner = null;
      expiresAt = Instant.EPOCH;
      return true;
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}

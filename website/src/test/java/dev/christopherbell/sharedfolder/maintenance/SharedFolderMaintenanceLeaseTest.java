package dev.christopherbell.sharedfolder.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SharedFolderMaintenanceLeaseTest {

  @Test
  void onlyTheOwnerCanReleaseAndReleasePermitsItsPeer() {
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T12:00:00Z"));
    InMemoryLeaseStore store = new InMemoryLeaseStore();
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
    InMemoryLeaseStore store = new InMemoryLeaseStore();
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

    @Override
    public synchronized boolean tryAcquire(
        String ownerToken, Instant acquiredAt, Instant newExpiresAt) {
      if (owner != null && !owner.equals(ownerToken) && expiresAt.isAfter(acquiredAt)) return false;
      owner = ownerToken;
      expiresAt = newExpiresAt;
      return true;
    }

    @Override
    public synchronized boolean renew(
        String ownerToken, Instant renewedAt, Instant newExpiresAt) {
      if (!ownerToken.equals(owner) || !expiresAt.isAfter(renewedAt)) return false;
      expiresAt = newExpiresAt;
      return true;
    }

    @Override
    public synchronized boolean release(String ownerToken) {
      if (!ownerToken.equals(owner)) return false;
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

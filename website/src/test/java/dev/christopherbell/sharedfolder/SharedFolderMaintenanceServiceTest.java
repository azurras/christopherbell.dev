package dev.christopherbell.sharedfolder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceHostLock;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceService;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLease;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.media.MediaPlaybackService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedFolderMaintenanceServiceTest {
  @TempDir Path temp;

  @Test
  void disabledFeatureDoesNoMaintenanceWork() {
    Fixture fixture = fixture(false);

    assertFalse(fixture.service.maintain());

    verifyNoInteractions(
        fixture.uploads, fixture.recycle, fixture.media, fixture.audit, fixture.hostLock,
        fixture.lease);
  }

  @Test
  void runsEveryBoundedMaintenanceContract() {
    Fixture fixture = fixture(true);

    assertTrue(fixture.service.maintain());

    verify(fixture.uploads).expireAbandoned();
    verify(fixture.recycle).cleanupExpired();
    verify(fixture.media).evictReadyCache();
    verify(fixture.media).reconcileWorkerStatuses();
    verify(fixture.lease).release();
    verify(fixture.hostHandle).close();
  }

  @Test
  void oneStepFailureDoesNotStopLaterStepsOrLaterRuns() {
    Fixture fixture = fixture(true);
    when(fixture.uploads.expireAbandoned())
        .thenThrow(new IllegalStateException("database unavailable"))
        .thenReturn(0);

    assertTrue(fixture.service.maintain());
    assertTrue(fixture.service.maintain());

    verify(fixture.uploads, times(2)).expireAbandoned();
    verify(fixture.recycle, times(2)).cleanupExpired();
    verify(fixture.media, times(2)).evictReadyCache();
    verify(fixture.media, times(2)).reconcileWorkerStatuses();
    verify(fixture.audit).recordSystemFailure(
        org.mockito.ArgumentMatchers.eq("MAINTENANCE_UPLOAD_EXPIRY_FAILED"),
        org.mockito.ArgumentMatchers.eq("maintenance"),
        org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.any(IllegalStateException.class));
    verify(fixture.lease, times(2)).release();
  }

  @Test
  void auditFailureStillAllowsLaterMaintenanceSteps() {
    Fixture fixture = fixture(true);
    when(fixture.uploads.expireAbandoned())
        .thenThrow(new IllegalStateException("database unavailable"));
    org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
        .when(fixture.audit).recordSystemFailure(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    assertTrue(fixture.service.maintain());

    verify(fixture.recycle).cleanupExpired();
    verify(fixture.media).evictReadyCache();
    verify(fixture.media).reconcileWorkerStatuses();
  }

  @Test
  void contendedDurableLeaseDoesNoMaintenanceWork() {
    Fixture fixture = fixture(true);
    when(fixture.lease.acquire()).thenReturn(false);

    assertFalse(fixture.service.maintain());

    verify(fixture.lease).acquire();
    verify(fixture.lease, org.mockito.Mockito.never()).release();
    verify(fixture.hostHandle).close();
    verifyNoInteractions(fixture.uploads, fixture.recycle, fixture.media);
  }

  @Test
  void hostLockPreventsOverlapAfterTheMongoLeaseExpiresAndThenPermitsThePeer()
      throws Exception {
    SharedLeaseStore store = new SharedLeaseStore();
    MutableClock clock = new MutableClock(Instant.parse("2026-07-22T12:00:00Z"));
    SharedFolderMaintenanceLease firstLease = new SharedFolderMaintenanceLease(
        store, clock, Duration.ofMinutes(30), () -> "owner-a");
    SharedFolderMaintenanceLease peerLease = new SharedFolderMaintenanceLease(
        store, clock, Duration.ofMinutes(30), () -> "owner-b");
    Path systemRoot = Files.createDirectory(temp.resolve("expiry-system-root"));
    SharedFolderMaintenanceHostLock firstHostLock =
        new SharedFolderMaintenanceHostLock(systemRoot);
    SharedFolderMaintenanceHostLock peerHostLock =
        new SharedFolderMaintenanceHostLock(systemRoot);
    SharedFolderProperties properties = mock(SharedFolderProperties.class);
    org.mockito.Mockito.when(properties.enabled()).thenReturn(true);
    SharedFolderUploadService firstUploads = mock(SharedFolderUploadService.class);
    SharedFolderRecycleService firstRecycle = mock(SharedFolderRecycleService.class);
    MediaPlaybackService firstMedia = mock(MediaPlaybackService.class);
    SharedFolderUploadService peerUploads = mock(SharedFolderUploadService.class);
    SharedFolderRecycleService peerRecycle = mock(SharedFolderRecycleService.class);
    MediaPlaybackService peerMedia = mock(MediaPlaybackService.class);
    SharedFolderAuditRecorder audit = mock(SharedFolderAuditRecorder.class);
    SharedFolderMaintenanceService first = new SharedFolderMaintenanceService(
        properties, firstUploads, firstRecycle, firstMedia, audit, firstHostLock, firstLease);
    SharedFolderMaintenanceService peer = new SharedFolderMaintenanceService(
        properties, peerUploads, peerRecycle, peerMedia, audit, peerHostLock, peerLease);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return 0;
    }).when(firstUploads).expireAbandoned();

    try (var executor = Executors.newSingleThreadExecutor()) {
      var active = executor.submit(first::maintain);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      clock.advance(Duration.ofMinutes(31));
      assertFalse(peer.maintain());
      verifyNoInteractions(peerUploads, peerRecycle, peerMedia);
      release.countDown();
      assertFalse(active.get(5, TimeUnit.SECONDS));
    }

    assertTrue(peer.maintain());
    verify(firstUploads).expireAbandoned();
    verify(peerUploads).expireAbandoned();
  }

  @Test
  void hostLockContentionDoesNotAcquireMongoLeaseOrRunEffects() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("contention-system-root"));
    SharedFolderMaintenanceHostLock owner = new SharedFolderMaintenanceHostLock(systemRoot);
    SharedFolderMaintenanceHostLock contender = new SharedFolderMaintenanceHostLock(systemRoot);
    Fixture fixture = fixture(true, contender);

    try (SharedFolderMaintenanceHostLock.Handle ignored = owner.tryAcquire().orElseThrow()) {
      assertFalse(fixture.service.maintain());
    }

    verifyNoInteractions(fixture.lease, fixture.uploads, fixture.recycle, fixture.media);
  }

  @Test
  void mongoContentionReleasesTheHostLockForAPeer() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("mongo-contention-system-root"));
    Fixture fixture = fixture(true, new SharedFolderMaintenanceHostLock(systemRoot));
    when(fixture.lease.acquire()).thenReturn(false);

    assertFalse(fixture.service.maintain());

    try (SharedFolderMaintenanceHostLock.Handle ignored =
        new SharedFolderMaintenanceHostLock(systemRoot).tryAcquire().orElseThrow()) {
      verifyNoInteractions(fixture.uploads, fixture.recycle, fixture.media);
    }
  }

  @Test
  void mongoLeaseFailureReleasesTheHostLockForAPeer() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("mongo-failure-system-root"));
    Fixture fixture = fixture(true, new SharedFolderMaintenanceHostLock(systemRoot));
    when(fixture.lease.acquire()).thenThrow(new IllegalStateException("database unavailable"));

    assertFalse(fixture.service.maintain());

    try (SharedFolderMaintenanceHostLock.Handle ignored =
        new SharedFolderMaintenanceHostLock(systemRoot).tryAcquire().orElseThrow()) {
      verifyNoInteractions(fixture.uploads, fixture.recycle, fixture.media);
    }
  }

  @Test
  void renewalFailureReleasesTheHostLockForAPeer() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("renewal-system-root"));
    Fixture fixture = fixture(true, new SharedFolderMaintenanceHostLock(systemRoot));
    when(fixture.lease.renew()).thenReturn(false);

    assertFalse(fixture.service.maintain());

    try (SharedFolderMaintenanceHostLock.Handle ignored =
        new SharedFolderMaintenanceHostLock(systemRoot).tryAcquire().orElseThrow()) {
      verify(fixture.uploads).expireAbandoned();
    }
  }

  @Test
  void thrownMaintenanceFaultReleasesTheHostLockForAPeer() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("exception-system-root"));
    Fixture fixture = fixture(true, new SharedFolderMaintenanceHostLock(systemRoot));
    when(fixture.uploads.expireAbandoned()).thenThrow(new AssertionError("fatal maintenance fault"));

    assertThrows(AssertionError.class, fixture.service::maintain);

    try (SharedFolderMaintenanceHostLock.Handle ignored =
        new SharedFolderMaintenanceHostLock(systemRoot).tryAcquire().orElseThrow()) {
      verify(fixture.lease).release();
    }
  }

  @Test
  void overlappingRunSkipsWithoutReleasingTheActiveRunLock() throws Exception {
    Fixture fixture = fixture(true);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return null;
    }).when(fixture.uploads).expireAbandoned();

    try (var executor = Executors.newSingleThreadExecutor()) {
      var active = executor.submit(fixture.service::maintain);
      assertTrue(entered.await(5, TimeUnit.SECONDS));

      assertFalse(fixture.service.maintain());
      release.countDown();
      assertTrue(active.get(5, TimeUnit.SECONDS));
    }

    verify(fixture.uploads).expireAbandoned();
    verify(fixture.recycle).cleanupExpired();
  }

  private Fixture fixture(boolean enabled) {
    SharedFolderMaintenanceHostLock hostLock = mock(SharedFolderMaintenanceHostLock.class);
    SharedFolderMaintenanceHostLock.Handle hostHandle =
        mock(SharedFolderMaintenanceHostLock.Handle.class);
    when(hostLock.tryAcquire()).thenReturn(java.util.Optional.of(hostHandle));
    return fixture(enabled, hostLock, hostHandle);
  }

  private Fixture fixture(boolean enabled, SharedFolderMaintenanceHostLock hostLock) {
    return fixture(enabled, hostLock, null);
  }

  private Fixture fixture(
      boolean enabled,
      SharedFolderMaintenanceHostLock hostLock,
      SharedFolderMaintenanceHostLock.Handle hostHandle) {
    SharedFolderProperties properties = mock(SharedFolderProperties.class);
    org.mockito.Mockito.when(properties.enabled()).thenReturn(enabled);
    SharedFolderUploadService uploads = mock(SharedFolderUploadService.class);
    SharedFolderRecycleService recycle = mock(SharedFolderRecycleService.class);
    MediaPlaybackService media = mock(MediaPlaybackService.class);
    SharedFolderAuditRecorder audit = mock(SharedFolderAuditRecorder.class);
    SharedFolderMaintenanceLease lease = mock(SharedFolderMaintenanceLease.class);
    when(lease.acquire()).thenReturn(true);
    when(lease.renew()).thenReturn(true);
    when(lease.release()).thenReturn(true);
    return new Fixture(
        new SharedFolderMaintenanceService(
            properties, uploads, recycle, media, audit, hostLock, lease),
        uploads, recycle, media, audit, hostLock, hostHandle, lease);
  }

  private record Fixture(
      SharedFolderMaintenanceService service,
      SharedFolderUploadService uploads,
      SharedFolderRecycleService recycle,
      MediaPlaybackService media,
      SharedFolderAuditRecorder audit,
      SharedFolderMaintenanceHostLock hostLock,
      SharedFolderMaintenanceHostLock.Handle hostHandle,
      SharedFolderMaintenanceLease lease) {}

  private static final class SharedLeaseStore implements SharedFolderMaintenanceLeaseStore {
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

package dev.christopherbell.sharedfolder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceService;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLease;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.media.MediaPlaybackService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SharedFolderMaintenanceServiceTest {

  @Test
  void disabledFeatureDoesNoMaintenanceWork() {
    Fixture fixture = fixture(false);

    assertFalse(fixture.service.maintain());

    verifyNoInteractions(
        fixture.uploads, fixture.recycle, fixture.media, fixture.audit, fixture.lease);
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
    verifyNoInteractions(fixture.uploads, fixture.recycle, fixture.media);
  }

  @Test
  void twoServiceInstancesCannotOverlapAndReleasePermitsThePeer() throws Exception {
    SharedLeaseStore store = new SharedLeaseStore();
    Clock clock = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    SharedFolderMaintenanceLease firstLease = new SharedFolderMaintenanceLease(
        store, clock, Duration.ofMinutes(30), () -> "owner-a");
    SharedFolderMaintenanceLease peerLease = new SharedFolderMaintenanceLease(
        store, clock, Duration.ofMinutes(30), () -> "owner-b");
    SharedFolderProperties properties = mock(SharedFolderProperties.class);
    org.mockito.Mockito.when(properties.enabled()).thenReturn(true);
    SharedFolderUploadService uploads = mock(SharedFolderUploadService.class);
    SharedFolderRecycleService recycle = mock(SharedFolderRecycleService.class);
    MediaPlaybackService media = mock(MediaPlaybackService.class);
    SharedFolderAuditRecorder audit = mock(SharedFolderAuditRecorder.class);
    SharedFolderMaintenanceService first = new SharedFolderMaintenanceService(
        properties, uploads, recycle, media, audit, firstLease);
    SharedFolderMaintenanceService peer = new SharedFolderMaintenanceService(
        properties, uploads, recycle, media, audit, peerLease);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return 0;
    }).when(uploads).expireAbandoned();

    try (var executor = Executors.newSingleThreadExecutor()) {
      var active = executor.submit(first::maintain);
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertFalse(peer.maintain());
      release.countDown();
      assertTrue(active.get(5, TimeUnit.SECONDS));
    }

    assertTrue(peer.maintain());
    verify(uploads, times(2)).expireAbandoned();
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
        new SharedFolderMaintenanceService(properties, uploads, recycle, media, audit, lease),
        uploads, recycle, media, audit, lease);
  }

  private record Fixture(
      SharedFolderMaintenanceService service,
      SharedFolderUploadService uploads,
      SharedFolderRecycleService recycle,
      MediaPlaybackService media,
      SharedFolderAuditRecorder audit,
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
}

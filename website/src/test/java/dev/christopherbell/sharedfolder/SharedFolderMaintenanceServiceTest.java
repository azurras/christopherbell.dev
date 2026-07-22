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
import dev.christopherbell.sharedfolder.media.MediaPlaybackService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SharedFolderMaintenanceServiceTest {

  @Test
  void disabledFeatureDoesNoMaintenanceWork() {
    Fixture fixture = fixture(false);

    assertFalse(fixture.service.maintain());

    verifyNoInteractions(fixture.uploads, fixture.recycle, fixture.media, fixture.audit);
  }

  @Test
  void runsEveryBoundedMaintenanceContract() {
    Fixture fixture = fixture(true);

    assertTrue(fixture.service.maintain());

    verify(fixture.uploads).expireAbandoned();
    verify(fixture.recycle).cleanupExpired();
    verify(fixture.media).evictReadyCache();
    verify(fixture.media).reconcileWorkerStatuses();
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
    return new Fixture(
        new SharedFolderMaintenanceService(properties, uploads, recycle, media, audit),
        uploads, recycle, media, audit);
  }

  private record Fixture(
      SharedFolderMaintenanceService service,
      SharedFolderUploadService uploads,
      SharedFolderRecycleService recycle,
      MediaPlaybackService media,
      SharedFolderAuditRecorder audit) {}
}

package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.media.MediaPlaybackService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Coordinates bounded shared-folder retention and cache maintenance. */
@Service
@Slf4j
public final class SharedFolderMaintenanceService {
  private final SharedFolderProperties properties;
  private final SharedFolderUploadService uploads;
  private final SharedFolderRecycleService recycle;
  private final MediaPlaybackService media;
  private final SharedFolderAuditRecorder audit;
  private final AtomicBoolean running = new AtomicBoolean();

  public SharedFolderMaintenanceService(
      SharedFolderProperties properties,
      SharedFolderUploadService uploads,
      SharedFolderRecycleService recycle,
      MediaPlaybackService media,
      SharedFolderAuditRecorder audit) {
    this.properties = properties;
    this.uploads = uploads;
    this.recycle = recycle;
    this.media = media;
    this.audit = audit;
  }

  /** Runs one non-overlapping maintenance pass; returns false when disabled or already active. */
  @Scheduled(fixedDelayString = "${app.shared-folder.maintenance-delay:PT15M}")
  public boolean maintain() {
    if (!properties.enabled() || !running.compareAndSet(false, true)) return false;
    try {
      step("MAINTENANCE_UPLOAD_EXPIRY_FAILED", uploads::expireAbandoned);
      step("MAINTENANCE_RECYCLE_FAILED", recycle::cleanupExpired);
      step("MAINTENANCE_CACHE_FAILED", media::evictReadyCache);
      step("MAINTENANCE_WORKER_FAILED", media::reconcileWorkerStatuses);
      return true;
    } finally {
      running.set(false);
    }
  }

  private void step(String action, Runnable work) {
    try {
      work.run();
    } catch (RuntimeException failure) {
      try {
        audit.recordSystemFailure(action, "maintenance", null, failure);
      } catch (RuntimeException auditFailure) {
        log.warn("Shared-folder maintenance failure audit could not be recorded");
      }
      log.warn("Shared-folder maintenance step failed: {}", action);
    }
  }
}

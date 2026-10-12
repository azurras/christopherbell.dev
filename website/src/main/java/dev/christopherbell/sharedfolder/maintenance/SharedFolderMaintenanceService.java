package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.media.MediaPlaybackService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final SharedFolderMaintenanceHostLock hostLock;
  private final SharedFolderMaintenanceLease lease;
  private final AtomicBoolean running = new AtomicBoolean();

  @Autowired
  public SharedFolderMaintenanceService(
      SharedFolderProperties properties,
      SharedFolderUploadService uploads,
      SharedFolderRecycleService recycle,
      MediaPlaybackService media,
      SharedFolderAuditRecorder audit,
      SharedFolderMaintenanceHostLock hostLock,
      SharedFolderMaintenanceLease lease) {
    this.properties = properties;
    this.uploads = uploads;
    this.recycle = recycle;
    this.media = media;
    this.audit = audit;
    this.hostLock = hostLock;
    this.lease = lease;
  }

  /** Runs one non-overlapping maintenance pass; returns false when disabled or already active. */
  @Scheduled(fixedDelayString = "${app.shared-folder.maintenance-delay:PT15M}")
  public boolean maintain() {
    if (!properties.enabled() || !running.compareAndSet(false, true)) {
      return false;
    }
    Optional<SharedFolderMaintenanceHostLock.Handle> hostLockHandle = Optional.empty();
    boolean acquired = false;
    try {
      try {
        hostLockHandle = hostLock.tryAcquire();
      } catch (RuntimeException failure) {
        recordFailure("MAINTENANCE_HOST_LOCK_FAILED", failure);
        return false;
      }
      if (hostLockHandle.isEmpty()) {
        return false;
      }
      try {
        acquired = lease.acquire();
      } catch (RuntimeException failure) {
        recordFailure("MAINTENANCE_LEASE_FAILED", failure);
        return false;
      }
      if (!acquired) {
        return false;
      }
      return runStepsRenewingBetween(List.of(
          new MaintenanceStep("MAINTENANCE_UPLOAD_EXPIRY_FAILED", uploads::expireAbandoned),
          new MaintenanceStep("MAINTENANCE_RECYCLE_FAILED", recycle::cleanupExpired),
          new MaintenanceStep("MAINTENANCE_MEDIA_CLEANUP_FAILED", media::cleanupTerminalJobs),
          new MaintenanceStep("MAINTENANCE_CACHE_FAILED", media::evictReadyCache),
          new MaintenanceStep("MAINTENANCE_WORKER_FAILED", media::reconcileWorkerStatuses)));
    } finally {
      if (acquired) {
        releaseLease();
      }
      hostLockHandle.ifPresent(this::releaseHostLock);
      running.set(false);
    }
  }

  /**
   * Runs each step in order, renewing the lease before every step after the first.
   *
   * @return false when a renewal fails, which stops the pass
   */
  private boolean runStepsRenewingBetween(List<MaintenanceStep> steps) {
    for (int index = 0; index < steps.size(); index++) {
      if (index > 0 && !renewLease()) {
        return false;
      }
      var maintenanceStep = steps.get(index);
      step(maintenanceStep.failureAction(), maintenanceStep.work());
    }
    return true;
  }

  /** One isolated maintenance step and the audit action recorded when it fails. */
  private record MaintenanceStep(String failureAction, Runnable work) {}

  private void step(String action, Runnable work) {
    try {
      work.run();
    } catch (RuntimeException failure) {
      recordFailure(action, failure);
      log.warn("Shared-folder maintenance step failed: {}", action);
    }
  }

  private void recordFailure(String action, RuntimeException failure) {
    try {
      audit.recordSystemFailure(action, "maintenance", null, failure);
    } catch (RuntimeException auditFailure) {
      log.warn("Shared-folder maintenance failure audit could not be recorded");
    }
  }

  private void releaseLease() {
    try {
      if (!lease.release()) {
        log.warn("Shared-folder maintenance lease release was not owned");
      }
    } catch (RuntimeException releaseFailure) {
      try {
        audit.recordSystemFailure(
            "MAINTENANCE_LEASE_RELEASE_FAILED", "maintenance", null, releaseFailure);
      } catch (RuntimeException auditFailure) {
        log.warn("Shared-folder maintenance lease release audit could not be recorded");
      }
      log.warn("Shared-folder maintenance lease release failed");
    }
  }

  private void releaseHostLock(SharedFolderMaintenanceHostLock.Handle handle) {
    try {
      handle.close();
    } catch (RuntimeException releaseFailure) {
      recordFailure("MAINTENANCE_HOST_LOCK_RELEASE_FAILED", releaseFailure);
      log.warn("Shared-folder maintenance host lock release failed");
    }
  }

  private boolean renewLease() {
    try {
      if (lease.renew()) {
        return true;
      }
      recordFailure(
          "MAINTENANCE_LEASE_RENEW_FAILED",
          new IllegalStateException("Shared-folder maintenance lease was lost"));
    } catch (RuntimeException renewalFailure) {
      recordFailure("MAINTENANCE_LEASE_RENEW_FAILED", renewalFailure);
    }
    log.warn("Shared-folder maintenance lease renewal failed");
    return false;
  }
}

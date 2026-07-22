package dev.christopherbell.sharedfolder.maintenance;

import java.time.Instant;

/** Atomic persistence boundary for the one fixed shared-folder maintenance lease. */
public interface SharedFolderMaintenanceLeaseStore {
  boolean tryAcquire(String ownerToken, Instant acquiredAt, Instant expiresAt);

  boolean renew(String ownerToken, Instant renewedAt, Instant expiresAt);

  boolean release(String ownerToken);
}

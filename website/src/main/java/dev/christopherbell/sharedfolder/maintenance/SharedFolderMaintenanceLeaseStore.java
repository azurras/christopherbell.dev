package dev.christopherbell.sharedfolder.maintenance;

import dev.christopherbell.libs.lease.LeaseGrant;
import java.time.Duration;
import java.util.Optional;

/** Atomic persistence boundary for the one fixed shared-folder maintenance lease. */
public interface SharedFolderMaintenanceLeaseStore {
  Optional<LeaseGrant> tryAcquire(String ownerToken, Duration duration);

  Optional<LeaseGrant> renew(LeaseGrant grant, Duration duration);

  boolean release(LeaseGrant grant);
}

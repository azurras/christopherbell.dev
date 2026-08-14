package dev.christopherbell.libs.lease;

import java.time.Instant;

/** Database-issued lease ownership proof with a monotonic fencing token. */
public record LeaseGrant(String leaseName, String ownerId, long fenceToken, Instant expiresAt) {
  public LeaseGrant {
    new LeaseIdentity(leaseName, ownerId);
    if (fenceToken < 1 || expiresAt == null) {
      throw new IllegalArgumentException("Lease grant is invalid.");
    }
  }
}

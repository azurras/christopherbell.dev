package dev.christopherbell.libs.lease;

import java.time.Instant;

/** Database-issued lease ownership proof with a monotonic fencing token. */
public record LeaseGrant(String leaseName, String ownerId, long fenceToken, Instant expiresAt) {
  public LeaseGrant {
    if (leaseName == null || leaseName.isBlank() || leaseName.length() > 128
        || ownerId == null || ownerId.isBlank() || ownerId.length() > 128
        || fenceToken < 1 || expiresAt == null) {
      throw new IllegalArgumentException("Lease grant is invalid.");
    }
  }
}

package dev.christopherbell.libs.lease;

/** Validated stable identity shared by database-backed lease requests and grants. */
public record LeaseIdentity(String leaseName, String ownerId) {
  public LeaseIdentity {
    if (leaseName == null || leaseName.isBlank() || leaseName.length() > 128
        || ownerId == null || ownerId.isBlank() || ownerId.length() > 128) {
      throw new IllegalArgumentException("Lease identity is invalid.");
    }
  }
}

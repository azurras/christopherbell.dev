package dev.christopherbell.libs.lease;

/** Raised when a collector can no longer prove ownership of its lease. */
public final class LeaseOwnershipLostException extends IllegalStateException {
  public LeaseOwnershipLostException(String leaseName) {
    super("Collector lease ownership was lost for " + leaseName + ".");
  }
}

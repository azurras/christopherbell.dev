package dev.christopherbell.libs.mongo.lease;

/** Verifies that a long-running collector still owns its renewable lease. */
@FunctionalInterface
public interface CollectorLeaseGuard {
  CollectorLeaseGuard NONE = () -> {};

  void verifyHeld();
}

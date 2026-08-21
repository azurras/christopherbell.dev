package dev.christopherbell.whatsforlunch.restaurant.importing;

import dev.christopherbell.libs.lease.CollectorLeaseGuard;

/** Verifies that an import writer still owns its lease before another mutation. */
@FunctionalInterface
public interface RestaurantImportLeaseGuard extends CollectorLeaseGuard {
  RestaurantImportLeaseGuard NONE = () -> {};

  void verifyHeld();
}

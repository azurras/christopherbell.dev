package dev.christopherbell.whatsforlunch.restaurant.importing;

/** Verifies that an import writer still owns its lease before another mutation. */
@FunctionalInterface
public interface RestaurantImportLeaseGuard {
  RestaurantImportLeaseGuard NONE = () -> {};

  void verifyHeld();
}

package dev.christopherbell.whatsforlunch.restaurant.importing;

/** Durable lifecycle status for an import attempt. */
public enum RestaurantImportRunStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED_LOCKED
}

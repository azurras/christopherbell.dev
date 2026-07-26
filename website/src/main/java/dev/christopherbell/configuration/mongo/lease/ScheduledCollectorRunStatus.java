package dev.christopherbell.configuration.mongo.lease;

/** Durable lifecycle state for a coordinated collector attempt. */
public enum ScheduledCollectorRunStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED_LOCKED
}

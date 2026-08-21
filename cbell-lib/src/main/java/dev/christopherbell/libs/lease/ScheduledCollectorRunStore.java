package dev.christopherbell.libs.lease;

/** Durable collector-run history supplied by the consuming application. */
public interface ScheduledCollectorRunStore {
  ScheduledCollectorRun save(ScheduledCollectorRun run);
}

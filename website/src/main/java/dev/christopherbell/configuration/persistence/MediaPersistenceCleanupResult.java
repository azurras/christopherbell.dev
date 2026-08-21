package dev.christopherbell.configuration.persistence;

/** Observable outcome of one bounded media-persistence retention batch. */
public record MediaPersistenceCleanupResult(
    int musicAccessAttemptsDeleted,
    int sharedAuditEventsDeleted) {

  public MediaPersistenceCleanupResult {
    if (musicAccessAttemptsDeleted < 0 || sharedAuditEventsDeleted < 0) {
      throw new IllegalArgumentException("Persistence cleanup counts cannot be negative.");
    }
  }

  public int totalDeleted() {
    return Math.addExact(musicAccessAttemptsDeleted, sharedAuditEventsDeleted);
  }
}

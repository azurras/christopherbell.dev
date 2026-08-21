package dev.christopherbell.notification.delivery;

/** Observable bounded cleanup result for notification dedupe and rate rows. */
public record NotificationCleanupResult(int guardsDeleted, int ratesDeleted) {
  public NotificationCleanupResult {
    if (guardsDeleted < 0 || ratesDeleted < 0) {
      throw new IllegalArgumentException("Cleanup counts cannot be negative");
    }
  }

  public int totalDeleted() {
    return guardsDeleted + ratesDeleted;
  }
}

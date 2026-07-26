package dev.christopherbell.notification.delivery;

import dev.christopherbell.notification.model.NotificationType;

/** Stable identity shared by dedupe and per-actor notification rate limits. */
public record NotificationEventIdentity(
    String recipientAccountId,
    String actorAccountId,
    NotificationType type,
    String targetId) {

  public NotificationEventIdentity {
    requireValue(recipientAccountId, "recipient");
    requireValue(actorAccountId, "actor");
    requireValue(targetId, "target");
    if (type == null) {
      throw new IllegalArgumentException("Notification type is required.");
    }
  }

  private static void requireValue(String value, String label) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("Notification " + label + " is invalid.");
    }
  }
}

package dev.christopherbell.notification.inbox;

/** Result of an atomic notification read-state update. */
public record NotificationReadResult(long updatedCount) {}

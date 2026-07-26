package dev.christopherbell.notification.delivery;

/** Proof that a notification event claimed its dedupe and rate-limit slots. */
public record NotificationDeliveryPermit(String claimId) {}

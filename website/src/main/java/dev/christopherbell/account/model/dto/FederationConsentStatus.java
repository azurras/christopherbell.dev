package dev.christopherbell.account.model.dto;

/** Authoritative federation consent and current server enrollment availability. */
public record FederationConsentStatus(boolean enabled, boolean enrollmentAvailable) {}

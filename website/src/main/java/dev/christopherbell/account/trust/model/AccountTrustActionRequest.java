package dev.christopherbell.account.trust.model;

/** Request body for setting a trust relationship with another account. */
public record AccountTrustActionRequest(AccountTrustType type) {}

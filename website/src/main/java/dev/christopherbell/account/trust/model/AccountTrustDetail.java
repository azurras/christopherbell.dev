package dev.christopherbell.account.trust.model;

import lombok.Builder;

/** API response describing a saved trust relationship. */
@Builder
public record AccountTrustDetail(
    String ownerAccountId,
    String targetAccountId,
    String targetUsername,
    AccountTrustType type
) {}

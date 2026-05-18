package dev.christopherbell.account.model.dto;

import dev.christopherbell.account.model.AccountStatus;
import lombok.Builder;

/**
 * Public account profile data safe for rendering on user-facing pages.
 */
@Builder
public record AccountProfile(
    String id,
    String username,
    AccountStatus status,
    long followerCount,
    long followingCount,
    boolean followedByMe,
    boolean self
) {}

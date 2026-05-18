package dev.christopherbell.account.model;

import lombok.Builder;

/**
 * DTO for requesting a password reset link.
 */
@Builder
public record AccountPasswordResetRequest(
    String email
) {}

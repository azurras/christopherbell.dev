package dev.christopherbell.account.model;

import lombok.Builder;

/**
 * DTO for completing a password reset with a token and new password.
 */
@Builder
public record AccountPasswordResetConfirmRequest(
    String token,
    String password
) {}

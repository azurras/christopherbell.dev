package dev.christopherbell.account.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * DTO for completing a password reset with a token and new password.
 */
@Builder
public record AccountPasswordResetConfirmRequest(
    @NotBlank @Size(max = 512) String token,
    @NotBlank @Size(min = 8, max = 128) String password
) {}

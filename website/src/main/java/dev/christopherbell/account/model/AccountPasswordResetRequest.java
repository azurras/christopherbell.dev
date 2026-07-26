package dev.christopherbell.account.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * DTO for requesting a password reset link.
 */
@Builder
public record AccountPasswordResetRequest(
    @NotBlank @Email @Size(max = 254) String email
) {}

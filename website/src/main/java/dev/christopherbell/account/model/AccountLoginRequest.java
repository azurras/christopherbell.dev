package dev.christopherbell.account.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * DTO for account login requests.
 */
@Builder
public record AccountLoginRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(max = 128) String password
) {}

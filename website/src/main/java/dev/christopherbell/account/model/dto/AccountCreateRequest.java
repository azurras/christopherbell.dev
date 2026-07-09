package dev.christopherbell.account.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * Request object for creating a new account.
 *
 * @param firstName The first name of the account holder.
 * @param lastName  The last name of the account holder.
 * @param email     The email address of the account holder.
 * @param password  The password for the account.
 * @param username  The desired username for the account.
 */
@Builder
public record AccountCreateRequest(
    @NotBlank
    @Size(max = 100)
    String firstName,
    @NotBlank
    @Size(max = 100)
    String lastName,
    @NotBlank
    @Email
    @Size(max = 254)
    String email,
    @NotBlank
    @Size(min = 8, max = 128)
    String password,
    @NotBlank
    @Size(min = 3, max = 50)
    @Pattern(regexp = "^[A-Za-z0-9._-]+$")
    String username
) {}

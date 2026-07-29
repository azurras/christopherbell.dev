package dev.christopherbell.account.model.dto;

import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import lombok.Builder;

/**
 * Request object for updating an existing account. Only non-null fields are applied.
 *
 * @param id         The unique identifier of the account to update (required)
 * @param firstName  Optional new first name
 * @param lastName   Optional new last name
 * @param email      Optional new email address
 * @param username   Optional new username
 * @param role       Optional new role
 * @param status     Optional new status
 * @param moderationReason Required audit reason when role or status changes
 */
@Builder
public record AccountUpdateRequest(
    String id,
    String firstName,
    String lastName,
    String email,
    String username,
    Role role,
    AccountStatus status,
    String moderationReason
) {}


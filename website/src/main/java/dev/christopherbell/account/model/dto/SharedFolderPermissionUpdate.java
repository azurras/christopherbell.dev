package dev.christopherbell.account.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Administrative request to set the shared-folder capabilities for an account.
 */
public record SharedFolderPermissionUpdate(
    @NotNull Boolean read,
    @NotNull Boolean write) {}

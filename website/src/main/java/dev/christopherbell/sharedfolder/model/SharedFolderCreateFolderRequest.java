package dev.christopherbell.sharedfolder.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Safe, decoded input for creating one direct shared-folder child directory. */
public record SharedFolderCreateFolderRequest(
    @NotNull String parentPath,
    @NotBlank String name) {}

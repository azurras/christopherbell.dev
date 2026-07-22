package dev.christopherbell.sharedfolder.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Safe decoded input for renaming one observed shared-folder item. */
public record SharedFolderRenameRequest(
    @NotBlank String path,
    @NotBlank String name,
    @NotBlank String observedToken) {}

package dev.christopherbell.sharedfolder.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Safe decoded input for moving one observed item to one direct destination child. */
public record SharedFolderMoveRequest(
    @NotBlank String path,
    @NotNull String destinationPath,
    @NotBlank String name,
    @NotBlank String observedToken,
    boolean replace,
    String replacedObservedToken) {}

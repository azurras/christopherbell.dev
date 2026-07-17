package dev.christopherbell.sharedfolder.model;

import jakarta.validation.constraints.NotBlank;

/** Safe decoded input for physically deleting one observed item before recycle support exists. */
public record SharedFolderDeleteRequest(
    @NotBlank String path,
    @NotBlank String observedToken) {}

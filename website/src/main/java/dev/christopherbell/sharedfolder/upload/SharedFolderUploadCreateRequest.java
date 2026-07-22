package dev.christopherbell.sharedfolder.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Safe decoded upload target and expected content metadata. */
public record SharedFolderUploadCreateRequest(
    @NotNull String parentPath,
    @NotBlank String name,
    @Positive long expectedBytes,
    String sha256,
    String targetObservedToken) {}

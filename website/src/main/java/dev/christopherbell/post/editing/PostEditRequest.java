package dev.christopherbell.post.editing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Validated replacement text for a bounded post edit. */
public record PostEditRequest(
    @NotBlank @Size(max = 280) String text) {}

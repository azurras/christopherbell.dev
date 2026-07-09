package dev.christopherbell.post.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * Request payload for creating a new post or reply.
 *
 * @param text the tweet‑like content to publish (trimmed, required, ≤ 280 chars)
 * @param parentId optional id of the parent post (when replying);
 *                 when provided, the new post becomes a child in that thread
 */
@Builder
public record PostCreateRequest(
    @NotBlank
    @Size(max = 280)
    String text,
    @Size(max = 100)
    String parentId
) {}

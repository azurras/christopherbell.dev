package dev.christopherbell.post.model;

import lombok.Builder;

/**
 * Stored metadata for one external URL mentioned by a post.
 */
@Builder
public record PostLinkPreview(
    String url,
    String domain,
    String title,
    String description,
    String imageUrl
) {}

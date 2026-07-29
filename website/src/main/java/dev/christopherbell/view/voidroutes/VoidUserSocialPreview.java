package dev.christopherbell.view.voidroutes;

/** Public, server-rendered metadata for a Void user page. */
public record VoidUserSocialPreview(
    String title,
    String description,
    String username,
    String heroMetadata
) {}

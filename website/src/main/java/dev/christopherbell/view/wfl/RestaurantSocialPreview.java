package dev.christopherbell.view.wfl;

/** Public, server-rendered metadata for a restaurant page. */
public record RestaurantSocialPreview(
    String title,
    String description,
    String name,
    String heroMetadata
) {}

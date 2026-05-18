package dev.christopherbell.whatsforlunch.restaurant.model;

import lombok.Builder;

/**
 * Summary of an external restaurant import.
 */
@Builder
public record RestaurantImportResult(
    String source,
    int fetched,
    int imported,
    int updated,
    int skippedExisting,
    int skippedInvalid
) {}

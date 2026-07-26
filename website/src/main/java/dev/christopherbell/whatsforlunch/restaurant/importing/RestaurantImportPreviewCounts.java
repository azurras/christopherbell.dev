package dev.christopherbell.whatsforlunch.restaurant.importing;

/** Read-only change counts calculated for an OpenStreetMap import. */
public record RestaurantImportPreviewCounts(
    int fetched,
    int created,
    int updated,
    int deleted,
    int unchanged,
    int invalid
) {}

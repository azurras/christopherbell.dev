package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;
import lombok.Builder;

/**
 * Summary of duplicate restaurant-name cleanup.
 */
@Builder
public record RestaurantDedupeResult(
    int duplicateGroups,
    int deleted,
    int updatedSurvivors,
    List<String> keptRestaurantIds,
    List<String> deletedRestaurantIds
) {}

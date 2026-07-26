package dev.christopherbell.whatsforlunch.restaurant.importing;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.List;

/** In-memory, single-request snapshot of remote import candidates. */
public record RestaurantImportSnapshot(
    String checksum,
    List<Restaurant> restaurants,
    RestaurantImportPreviewCounts counts,
    List<String> representativeChanges
) {
  public RestaurantImportSnapshot {
    restaurants = List.copyOf(restaurants);
    representativeChanges = List.copyOf(representativeChanges);
  }
}

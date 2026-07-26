package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Non-mutating preview of duplicate restaurant-name cleanup. */
public record RestaurantDedupePreview(List<RestaurantDedupeGroupPreview> groups) {
  public RestaurantDedupePreview {
    groups = List.copyOf(groups);
  }
}

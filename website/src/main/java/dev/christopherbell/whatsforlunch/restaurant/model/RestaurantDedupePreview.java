package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Non-mutating page of duplicate restaurant-name cleanup candidates. */
public record RestaurantDedupePreview(
    List<RestaurantDedupeGroupPreview> groups,
    String nextCursor
) {
  public RestaurantDedupePreview(List<RestaurantDedupeGroupPreview> groups) {
    this(groups, null);
  }

  public RestaurantDedupePreview {
    groups = List.copyOf(groups);
  }
}

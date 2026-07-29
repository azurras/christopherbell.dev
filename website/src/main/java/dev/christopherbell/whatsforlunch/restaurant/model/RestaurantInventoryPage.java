package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Stable bounded admin restaurant inventory response. */
public record RestaurantInventoryPage(
    List<RestaurantDetail> items,
    String nextCursor,
    long total
) {
  public RestaurantInventoryPage {
    items = List.copyOf(items);
  }
}

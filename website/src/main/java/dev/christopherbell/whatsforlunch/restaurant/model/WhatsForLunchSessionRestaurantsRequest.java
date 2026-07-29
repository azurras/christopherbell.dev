package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Host request to replace the three restaurants shown in a shared WFL session. */
public record WhatsForLunchSessionRestaurantsRequest(
    List<String> restaurantIds,
    long expectedRevision
) {
  /** Compatibility constructor for callers whose session is still at revision zero. */
  public WhatsForLunchSessionRestaurantsRequest(List<String> restaurantIds) {
    this(restaurantIds, 0);
  }
}

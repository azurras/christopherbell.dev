package dev.christopherbell.whatsforlunch.restaurant.model;

import java.time.Instant;
import java.util.List;

/** Bounded attribution for one host-authorized restaurant reset. */
public record WhatsForLunchRestaurantResetAudit(
    long revision,
    String accountId,
    String username,
    List<String> restaurantIds,
    Instant occurredOn
) {
  public WhatsForLunchRestaurantResetAudit {
    restaurantIds = List.copyOf(restaurantIds);
  }
}

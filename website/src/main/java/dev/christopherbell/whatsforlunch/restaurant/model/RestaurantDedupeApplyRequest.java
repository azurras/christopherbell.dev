package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Operator confirmation for one or more previewed duplicate groups. */
public record RestaurantDedupeApplyRequest(List<RestaurantDedupeConfirmation> groups) {
  public RestaurantDedupeApplyRequest {
    groups = groups == null ? List.of() : List.copyOf(groups);
  }
}

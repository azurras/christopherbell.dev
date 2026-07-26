package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Exact duplicate group state confirmed by an operator. */
public record RestaurantDedupeConfirmation(
    String normalizedName,
    String version,
    String survivorId,
    List<String> memberIds
) {
  public RestaurantDedupeConfirmation {
    memberIds = memberIds == null ? List.of() : List.copyOf(memberIds);
  }
}

package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Proposed stable survivor and exact membership for one duplicate-name group. */
public record RestaurantDedupeGroupPreview(
    String normalizedName,
    String version,
    String survivorId,
    List<String> memberIds,
    List<RestaurantDedupeCandidate> candidates
) {
  public RestaurantDedupeGroupPreview {
    memberIds = List.copyOf(memberIds);
    candidates = List.copyOf(candidates);
  }
}

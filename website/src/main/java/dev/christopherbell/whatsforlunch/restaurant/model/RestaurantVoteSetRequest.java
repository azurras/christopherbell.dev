package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Request to vote on a restaurant without putting provider ids in the URL path. */
public record RestaurantVoteSetRequest(String restaurantId, Object vote) {
  /** Rejects legacy and misspelled request properties at this transport boundary. */
  @JsonAnySetter
  public void rejectUnknownProperty(String property, Object ignoredValue) {
    throw new IllegalArgumentException("Unknown restaurant vote property: " + property);
  }
}

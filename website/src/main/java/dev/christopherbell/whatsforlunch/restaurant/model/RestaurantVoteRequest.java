package dev.christopherbell.whatsforlunch.restaurant.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** Request to set the caller's binary vote for a restaurant. */
public record RestaurantVoteRequest(Object vote) {
  /** Rejects legacy and misspelled request properties at this transport boundary. */
  @JsonAnySetter
  public void rejectUnknownProperty(String property, Object ignoredValue) {
    throw new IllegalArgumentException("Unknown restaurant vote property: " + property);
  }
}

package dev.christopherbell.whatsforlunch.restaurant.importing;

import java.time.Instant;
import java.util.List;

/** Public, non-sensitive summary of restaurant source freshness and coverage. */
public record RestaurantDataFreshness(
    String source,
    Instant lastRefreshedOn,
    boolean current,
    int currentWithinDays,
    List<String> cityCoverage
) {
  public RestaurantDataFreshness {
    cityCoverage = List.copyOf(cityCoverage);
  }
}

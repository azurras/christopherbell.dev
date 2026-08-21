package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;

/** Engine-neutral integrity rule for persisted restaurant locality and coordinates. */
final class RestaurantLocationIntegrity {
  private RestaurantLocationIntegrity() {}

  static void requireGenuine(Restaurant restaurant) {
    var address = restaurant == null ? null : restaurant.getAddress();
    if (restaurant == null || blank(restaurant.getId()) || blank(restaurant.getName())
        || blank(restaurant.getDedupeKey()) || blank(restaurant.getSearchCity())
        || blank(restaurant.getSearchState()) || address == null || blank(address.getCity())
        || blank(address.getState()) || blank(address.getCountry())
        || address.getLatitude() == null || address.getLongitude() == null
        || !Double.isFinite(address.getLatitude()) || !Double.isFinite(address.getLongitude())
        || Math.abs(address.getLatitude()) > 90 || Math.abs(address.getLongitude()) > 180
        || "Imported Metro".equalsIgnoreCase(address.getCity())) {
      throw new IllegalArgumentException(
          "Restaurant requires a genuine locality, state, country, and coordinates.");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}

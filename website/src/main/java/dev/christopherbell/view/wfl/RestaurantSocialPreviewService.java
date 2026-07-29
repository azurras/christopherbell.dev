package dev.christopherbell.view.wfl;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Builds public metadata from the canonical restaurant detail projection. */
@RequiredArgsConstructor
@Service
public class RestaurantSocialPreviewService {
  private final RestaurantService restaurants;

  /** Returns safe public metadata for an existing restaurant. */
  public RestaurantSocialPreview preview(String restaurantId) throws ResourceNotFoundException {
    try {
      var restaurant = restaurants.getRestaurantById(restaurantId);
      var name = valueOrFallback(restaurant.getName(), "Restaurant");
      var location = locationParts(
          restaurant.getAddress() == null ? null : restaurant.getAddress().getCity(),
          restaurant.getAddress() == null ? null : restaurant.getAddress().getState());
      var cuisine = valueOrFallback(restaurant.getCuisine(), "Restaurant");
      var heroMetadata = location.isEmpty() ? cuisine : cuisine + " restaurant in " + location;
      var description = heroMetadata + ". Details and ratings from What's For Lunch.";
      return new RestaurantSocialPreview("CB | " + name, description, name, heroMetadata + ".");
    } catch (InvalidRequestException exception) {
      throw new ResourceNotFoundException("Restaurant not found.");
    }
  }

  private static String locationParts(String city, String state) {
    List<String> parts = new ArrayList<>();
    if (city != null && !city.isBlank()) {
      parts.add(city.strip());
    }
    if (state != null && !state.isBlank()) {
      parts.add(state.strip());
    }
    return String.join(", ", parts);
  }

  private static String valueOrFallback(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }
}

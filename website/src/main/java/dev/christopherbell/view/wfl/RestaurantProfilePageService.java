package dev.christopherbell.view.wfl;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantService;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantWebsiteUrlPolicy;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds the immutable public page for one canonical restaurant detail. */
@RequiredArgsConstructor
@Service
public class RestaurantProfilePageService {
  private static final String PUBLIC_ROOT = "https://www.christopherbell.dev";
  private static final String MAPS_ROOT = "https://www.google.com/maps/search/";

  private final RestaurantService restaurants;
  private final ObjectMapper objectMapper;

  /** Looks up and maps one restaurant without exposing member or audit fields. */
  public RestaurantProfilePage profile(String restaurantId) throws ResourceNotFoundException {
    try {
      return build(restaurants.getRestaurantById(restaurantId));
    } catch (InvalidRequestException invalid) {
      throw new ResourceNotFoundException("Restaurant not found.");
    }
  }

  private RestaurantProfilePage build(RestaurantDetail detail) {
    var id = requiredValue(detail.getId(), "Restaurant id");
    var path = "/wfl/restaurants/"
        + UriUtils.encodePathSegment(id, StandardCharsets.UTF_8);
    var canonicalUrl = PUBLIC_ROOT + path;
    var name = valueOrFallback(detail.getName(), "Restaurant");
    var publicCuisine = valueOrNull(detail.getCuisine());
    var cuisine = publicCuisine == null ? "Restaurant" : publicCuisine;
    var address = publicAddress(detail.getAddress());
    var location = joinPresent(
        address == null ? null : address.city(),
        address == null ? null : address.state());
    var hero = location.isEmpty() ? cuisine : cuisine + " restaurant in " + location;
    var description = hero + ". Details and ratings from What's For Lunch.";
    var rating = publicRating(detail.getVoteCount(), detail.getUpVotes(), detail.getDownVotes());
    var website = RestaurantWebsiteUrlPolicy.safeOrNull(detail.getWebsite());
    var phone = valueOrNull(detail.getPhoneNumber());
    var source = firstPresent(detail.getSourceAmenity(), detail.getType());
    var directions = directionsUrl(name, address);
    var structuredData = structuredData(
        canonicalUrl, name, publicCuisine, address, rating, phone, website);

    return new RestaurantProfilePage(
        id,
        path,
        canonicalUrl,
        "CB | " + name,
        description,
        name,
        cuisine,
        hero + ".",
        address,
        rating,
        phone,
        website,
        source,
        directions,
        serializeForHtml(structuredData));
  }

  private static RestaurantProfilePage.Address publicAddress(
      dev.christopherbell.whatsforlunch.restaurant.model.Address address
  ) {
    if (address == null) {
      return null;
    }
    var latitude = validCoordinate(address.getLatitude(), -90.0, 90.0)
        ? address.getLatitude() : null;
    var longitude = validCoordinate(address.getLongitude(), -180.0, 180.0)
        ? address.getLongitude() : null;
    if (latitude == null || longitude == null) {
      latitude = null;
      longitude = null;
    }
    var result = new RestaurantProfilePage.Address(
        valueOrNull(address.getStreet1()),
        valueOrNull(address.getStreet2()),
        valueOrNull(address.getCity()),
        valueOrNull(address.getState()),
        valueOrNull(address.getPostalCode()),
        valueOrNull(address.getCountry()),
        latitude,
        longitude);
    return result.displayLine().isEmpty()
            && !result.hasCoordinates()
            && result.country() == null
        ? null : result;
  }

  private static RestaurantProfilePage.Rating publicRating(
      Integer voteCount,
      Integer upVotes,
      Integer downVotes
  ) {
    if (voteCount == null || upVotes == null || downVotes == null || voteCount <= 0
        || upVotes < 0 || downVotes < 0 || voteCount != upVotes + downVotes) {
      return null;
    }
    long score = (long) upVotes * 5L + downVotes;
    return score > Integer.MAX_VALUE ? null : new RestaurantProfilePage.Rating(voteCount, (int) score);
  }

  private static String directionsUrl(
      String name,
      RestaurantProfilePage.Address address
  ) {
    var destination = address != null && address.hasCoordinates()
        ? address.latitude() + "," + address.longitude()
        : joinPresent(name, address == null ? null : address.displayLine());
    return UriComponentsBuilder.fromUriString(MAPS_ROOT)
        .queryParam("api", "1")
        .queryParam("destination", destination)
        .build()
        .encode()
        .toUriString();
  }

  private Map<String, Object> structuredData(
      String canonicalUrl,
      String name,
      String cuisine,
      RestaurantProfilePage.Address address,
      RestaurantProfilePage.Rating rating,
      String phone,
      String website
  ) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("@context", "https://schema.org");
    json.put("@type", "Restaurant");
    json.put("name", name);
    if (cuisine != null) {
      json.put("servesCuisine", cuisine);
    }
    json.put("url", canonicalUrl);
    if (address != null && hasPostalAddress(address)) {
      json.put("address", schemaAddress(address));
    }
    if (address != null && address.hasCoordinates()) {
      json.put("geo", Map.of(
          "@type", "GeoCoordinates",
          "latitude", address.latitude(),
          "longitude", address.longitude()));
    }
    if (phone != null) {
      json.put("telephone", phone);
    }
    if (website != null) {
      json.put("sameAs", website);
    }
    if (rating != null) {
      json.put("aggregateRating", Map.of(
          "@type", "AggregateRating",
          "ratingValue", rating.average(),
          "ratingCount", rating.count(),
          "bestRating", 5,
          "worstRating", 1));
    }
    return json;
  }

  private static Map<String, Object> schemaAddress(RestaurantProfilePage.Address address) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("@type", "PostalAddress");
    putPresent(json, "streetAddress", joinPresent(address.street1(), address.street2()));
    putPresent(json, "addressLocality", address.city());
    putPresent(json, "addressRegion", address.state());
    putPresent(json, "postalCode", address.postalCode());
    putPresent(json, "addressCountry", address.country());
    return json;
  }

  private static boolean hasPostalAddress(RestaurantProfilePage.Address address) {
    return Stream.of(
        address.street1(),
        address.street2(),
        address.city(),
        address.state(),
        address.postalCode(),
        address.country()).anyMatch(value -> value != null && !value.isBlank());
  }

  private String serializeForHtml(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value)
          .replace("&", "\\u0026")
          .replace("<", "\\u003c")
          .replace(">", "\\u003e");
    } catch (JacksonException failure) {
      throw new IllegalStateException(
          "Restaurant structured data serialization failed", failure);
    }
  }

  private static void putPresent(Map<String, Object> target, String key, String value) {
    if (value != null && !value.isBlank()) {
      target.put(key, value);
    }
  }

  private static boolean validCoordinate(Double value, double minimum, double maximum) {
    return value != null
        && Double.isFinite(value)
        && value >= minimum
        && value <= maximum;
  }

  private static String requiredValue(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(label + " is missing.");
    }
    return value.strip();
  }

  private static String valueOrFallback(String value, String fallback) {
    var present = valueOrNull(value);
    return present == null ? fallback : present;
  }

  private static String valueOrNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String firstPresent(String... values) {
    for (var value : values) {
      var present = valueOrNull(value);
      if (present != null) {
        return present;
      }
    }
    return null;
  }

  private static String joinPresent(String... values) {
    List<String> present = new ArrayList<>();
    for (var value : values) {
      var normalized = valueOrNull(value);
      if (normalized != null) {
        present.add(normalized);
      }
    }
    return String.join(", ", present);
  }
}

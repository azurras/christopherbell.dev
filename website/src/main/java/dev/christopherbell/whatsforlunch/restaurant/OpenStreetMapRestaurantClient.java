package dev.christopherbell.whatsforlunch.restaurant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client for importing restaurant-like places from OpenStreetMap via Overpass.
 */
@Component
public class OpenStreetMapRestaurantClient {
  private static final List<String> FAST_FOOD_NAME_PARTS = List.of(
      "arbys",
      "burger king",
      "chick-fil-a",
      "chipotle",
      "domino's",
      "dominos",
      "jack in the box",
      "jimmy john",
      "kfc",
      "mcdonald",
      "panda express",
      "pizza hut",
      "popeyes",
      "sonic",
      "subway",
      "taco bell",
      "wendy's",
      "whataburger"
  );

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String endpoint;
  private final String bbox;
  private final int timeoutSeconds;
  private final int resultLimit;
  private final boolean includeFastFood;

  public OpenStreetMapRestaurantClient(
      ObjectMapper objectMapper,
      @Value("${wfl.restaurant-import.osm.endpoint:https://overpass-api.de/api/interpreter}") String endpoint,
      @Value("${wfl.restaurant-import.osm.bbox:29.95,-98.25,30.75,-97.15}") String bbox,
      @Value("${wfl.restaurant-import.osm.timeout-seconds:60}") int timeoutSeconds,
      @Value("${wfl.restaurant-import.osm.result-limit:5000}") int resultLimit,
      @Value("${wfl.restaurant-import.osm.include-fast-food:true}") boolean includeFastFood
  ) {
    this.objectMapper = objectMapper;
    this.endpoint = endpoint;
    this.bbox = bbox;
    this.timeoutSeconds = timeoutSeconds;
    this.resultLimit = resultLimit;
    this.includeFastFood = includeFastFood;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public List<Restaurant> getAustinMetroRestaurants()
      throws IOException, InterruptedException {
    var query = buildQuery();
    var request = HttpRequest.newBuilder(URI.create(endpoint))
        .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
        .timeout(Duration.ofSeconds(timeoutSeconds + 10L))
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("User-Agent", "christopherbell.dev whats-for-lunch importer")
        .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Overpass request failed with status " + response.statusCode());
    }

    return parseRestaurants(response.body());
  }

  private String buildQuery() {
    var amenityPattern = includeFastFood
        ? "^(restaurant|cafe|food_court|fast_food)$"
        : "^(restaurant|cafe|food_court)$";
    return """
        [out:json][timeout:%d];
        (
          node["amenity"~"%s"]["name"](%s);
          way["amenity"~"%s"]["name"](%s);
          relation["amenity"~"%s"]["name"](%s);
        );
        out center %d;
        """.formatted(timeoutSeconds, amenityPattern, bbox, amenityPattern, bbox, amenityPattern, bbox, resultLimit);
  }

  private List<Restaurant> parseRestaurants(String body) throws IOException {
    var root = objectMapper.readTree(body);
    var elements = root.path("elements");
    var restaurants = new ArrayList<Restaurant>();
    if (!elements.isArray()) {
      return restaurants;
    }

    for (JsonNode element : elements) {
      toRestaurant(element).ifPresent(restaurants::add);
    }
    restaurants.sort(Comparator
        .comparingInt(this::restaurantPreferenceRank)
        .thenComparing(restaurant -> normalize(restaurant.getName())));
    return restaurants;
  }

  private java.util.Optional<Restaurant> toRestaurant(JsonNode element) {
    var tags = element.path("tags");
    var name = text(tags, "name");
    if (name == null || name.isBlank()) {
      return java.util.Optional.empty();
    }

    return java.util.Optional.of(Restaurant.builder()
        .id("osm:" + element.path("type").asText() + ":" + element.path("id").asText())
        .name(name.strip())
        .address(Address.builder()
            .street1(street1(tags))
            .city(defaultText(text(tags, "addr:city"), "Austin Metro"))
            .state(defaultText(text(tags, "addr:state"), "TX"))
            .country(defaultText(text(tags, "addr:country"), "US"))
            .latitude(coordinate(element, "lat"))
            .longitude(coordinate(element, "lon"))
            .postalCode(text(tags, "addr:postcode"))
            .build())
        .cuisine(text(tags, "cuisine"))
        .phoneNumber(firstText(tags, "contact:phone", "phone"))
        .sourceAmenity(text(tags, "amenity"))
        .website(firstText(tags, "contact:website", "website"))
        .build());
  }

  private int restaurantPreferenceRank(Restaurant restaurant) {
    return isFastFood(restaurant) ? 1 : 0;
  }

  private boolean isFastFood(Restaurant restaurant) {
    if ("fast_food".equalsIgnoreCase(restaurant.getSourceAmenity())) {
      return true;
    }
    var normalizedName = normalize(restaurant.getName());
    return FAST_FOOD_NAME_PARTS.stream().anyMatch(normalizedName::contains);
  }

  private String street1(JsonNode tags) {
    var full = text(tags, "addr:full");
    if (full != null && !full.isBlank()) {
      return full;
    }
    var number = text(tags, "addr:housenumber");
    var street = text(tags, "addr:street");
    if (street == null || street.isBlank()) {
      return null;
    }
    return number == null || number.isBlank() ? street : number + " " + street;
  }

  private String firstText(JsonNode tags, String... keys) {
    for (String key : keys) {
      var value = text(tags, key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private String text(JsonNode node, String fieldName) {
    var value = node.path(fieldName);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  private Double coordinate(JsonNode element, String fieldName) {
    var direct = element.path(fieldName);
    if (direct.isNumber()) {
      return direct.asDouble();
    }

    var center = element.path("center").path(fieldName);
    return center.isNumber() ? center.asDouble() : null;
  }

  private String defaultText(String value, String defaultValue) {
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private String normalize(String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]", "");
  }
}

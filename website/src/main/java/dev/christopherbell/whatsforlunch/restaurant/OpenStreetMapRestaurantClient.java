package dev.christopherbell.whatsforlunch.restaurant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.christopherbell.libs.http.BoundedResponseBodyHandlers;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.model.Address;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Client for importing restaurant-like places from OpenStreetMap via Overpass.
 */
@Component
public class OpenStreetMapRestaurantClient {
  private static final long MAXIMUM_RESPONSE_BYTES = 16L * 1024 * 1024;
  private static final Map<String, String> STATE_NAMES_BY_ABBREVIATION = Map.of(
      "ca", "california",
      "la", "louisiana",
      "tx", "texas");

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final WflProperties.Osm properties;
  private final Map<String, List<SupportedLocation>> supportedLocations;

  public OpenStreetMapRestaurantClient(
      ObjectMapper objectMapper,
      WflProperties wflProperties
  ) {
    this.objectMapper = objectMapper;
    this.properties = wflProperties.getRestaurantImport().getOsm();
    this.supportedLocations = configuredLocations(properties.getMetros());
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  public List<Restaurant> getConfiguredMetroRestaurants()
      throws IOException, InterruptedException {
    var query = buildQuery();
    var request = HttpRequest.newBuilder(properties.getEndpoint())
        .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
        .timeout(properties.getTimeout().plusSeconds(10))
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("User-Agent", "christopherbell.dev whats-for-lunch importer")
        .build();

    var response = BoundedResponseBodyHandlers.send(
        httpClient,
        request,
        BoundedResponseBodyHandlers.ofString(
            MAXIMUM_RESPONSE_BYTES,
            StandardCharsets.UTF_8,
            status -> status >= 200 && status < 300));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Overpass request failed with status " + response.statusCode());
    }

    return parseRestaurants(response.body());
  }

  private String buildQuery() {
    var amenityPattern = properties.isIncludeFastFood()
        ? "^(restaurant|cafe|food_court|fast_food)$"
        : "^(restaurant|cafe|food_court)$";
    var clauses = String.join("\n", importBoundingBoxes().stream()
        .map(bbox -> """
              node["amenity"~"%s"]["name"](%s);
              way["amenity"~"%s"]["name"](%s);
              relation["amenity"~"%s"]["name"](%s);
            """.formatted(amenityPattern, bbox, amenityPattern, bbox, amenityPattern, bbox))
        .toList());
    return """
        [out:json][timeout:%d];
        (
        %s
        );
        out center %d;
        """.formatted(properties.getTimeout().toSeconds(), clauses, properties.getResultLimit());
  }

  private List<String> importBoundingBoxes() {
    return properties.getMetros().stream()
        .map(WflProperties.Metro::getBounds)
        .map(WflProperties.BoundingBox::toOverpassValue)
        .toList();
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
    restaurants.sort(Comparator.comparing(restaurant -> normalize(restaurant.getName())));
    return restaurants;
  }

  private Optional<Restaurant> toRestaurant(JsonNode element) {
    var tags = element.path("tags");
    var name = text(tags, "name");
    var latitude = coordinate(element, "lat");
    var longitude = coordinate(element, "lon");
    if (name == null || name.isBlank()
        || !isCoordinate(latitude, -90.0, 90.0)
        || !isCoordinate(longitude, -180.0, 180.0)) {
      return Optional.empty();
    }
    var location = supportedLocation(tags, latitude, longitude);
    if (location.isEmpty()) {
      return Optional.empty();
    }
    var supportedLocation = location.orElseThrow();

    return Optional.of(Restaurant.builder()
        .id("osm:" + element.path("type").asText() + ":" + element.path("id").asText())
        .name(name.strip())
        .address(Address.builder()
            .street1(street1(tags))
            .city(supportedLocation.city())
            .state(supportedLocation.state())
            .country("US")
            .latitude(latitude)
            .longitude(longitude)
            .postalCode(text(tags, "addr:postcode"))
            .build())
        .cuisine(text(tags, "cuisine"))
        .phoneNumber(firstText(tags, "contact:phone", "phone"))
        .sourceAmenity(text(tags, "amenity"))
        .website(RestaurantWebsiteUrlPolicy.safeOrNull(
            firstText(tags, "contact:website", "website")))
        .build());
  }

  private Optional<SupportedLocation> supportedLocation(
      JsonNode tags,
      double latitude,
      double longitude
  ) {
    var locality = firstText(tags, "addr:city", "addr:town", "addr:village", "addr:municipality");
    var suppliedState = text(tags, "addr:state");
    var suppliedCountry = text(tags, "addr:country");
    if (suppliedCountry != null
        && !suppliedCountry.isBlank()
        && !isUnitedStates(suppliedCountry)) {
      return Optional.empty();
    }

    var matches = supportedLocations.getOrDefault(normalizeLocation(locality), List.of()).stream()
        .filter(location -> location.contains(latitude, longitude))
        .filter(location -> suppliedState == null
            || suppliedState.isBlank()
            || stateMatches(suppliedState, location.state()))
        .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private Map<String, List<SupportedLocation>> configuredLocations(List<WflProperties.Metro> metros) {
    var locations = new LinkedHashMap<String, List<SupportedLocation>>();
    for (var metro : metros) {
      for (var city : metro.getCities()) {
        locations.computeIfAbsent(normalizeLocation(city), ignored -> new ArrayList<>())
            .add(new SupportedLocation(
                city.strip(), metro.getState().strip(), metro.getBounds()));
      }
    }
    locations.replaceAll((ignored, candidates) -> List.copyOf(candidates));
    return Map.copyOf(locations);
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

  private boolean isCoordinate(Double value, double minimum, double maximum) {
    return value != null
        && !value.isNaN()
        && !value.isInfinite()
        && value >= minimum
        && value <= maximum;
  }

  private boolean isUnitedStates(String value) {
    return List.of("us", "usa", "unitedstates").contains(normalizeLocation(value));
  }

  private boolean stateMatches(String suppliedState, String canonicalState) {
    var supplied = normalizeLocation(suppliedState);
    var abbreviation = normalizeLocation(canonicalState);
    return supplied.equals(abbreviation)
        || supplied.equals(STATE_NAMES_BY_ABBREVIATION.get(abbreviation));
  }

  private String normalizeLocation(String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private String normalize(String value) {
    return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 -]", "");
  }

  private record SupportedLocation(
      String city,
      String state,
      WflProperties.BoundingBox bounds
  ) {
    private boolean contains(double latitude, double longitude) {
      return latitude >= bounds.getSouth()
          && latitude <= bounds.getNorth()
          && longitude >= bounds.getWest()
          && longitude <= bounds.getEast();
    }
  }
}

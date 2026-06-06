package dev.christopherbell.canesboxtracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.canesboxtracker.model.CanesBoxMetroPrice;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;

/**
 * Official Raising Canes ordering API client used by the Box Index collector.
 */
@Component
public class OfficialCanesBoxPriceClient implements CanesBoxPriceClient {
  private static final String OFFICIAL_ORDER_BASE_URL = "https://order.raisingcanes.com";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final CanesBoxTrackerProperties properties;

  /**
   * Creates the official Cane's client.
   */
  public OfficialCanesBoxPriceClient(
      ObjectMapper objectMapper,
      CanesBoxTrackerProperties properties
  ) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .build();
  }

  /**
   * Fetches and parses a Box Combo price for one configured metro target.
   */
  @Override
  public CanesBoxMetroPrice fetchBoxComboPrice(CanesBoxTrackerProperties.MetroTarget target) {
    var officialFailures = new ArrayList<String>();
    try {
      var graphQlPrice = fetchGraphQlMenuPrice(target);
      if (graphQlPrice.isPresent()) {
        return graphQlPrice.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CanesBoxMetroPrice.failure(target, "Official Cane's GraphQL API request was interrupted.");
    } catch (Exception e) {
      officialFailures.add("Official Cane's GraphQL API failed: " + e.getMessage());
    }

    try {
      return fetchRestaurantByRefPrice(target);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CanesBoxMetroPrice.failure(target, "Official Cane's API request was interrupted.");
    } catch (Exception e) {
      officialFailures.add(e.getMessage());
      return fetchFallbackPrice(target, String.join("; ", officialFailures));
    }
  }

  private Optional<CanesBoxMetroPrice> fetchGraphQlMenuPrice(
      CanesBoxTrackerProperties.MetroTarget target
  ) throws Exception {
    if (!hasCoordinates(target) || properties.getGraphQlUrl() == null || properties.getGraphQlUrl().isBlank()) {
      return Optional.empty();
    }
    var restaurant = findNearestRestaurant(target)
        .orElseThrow(() -> new IllegalStateException("Official location search did not return an orderable restaurant."));
    var body = fetchRestaurantMenu(restaurant);
    var price = findBoxComboPrice(body)
        .orElseThrow(() -> new IllegalStateException("The Box Combo price was not found in the official menu."));
    var result = CanesBoxMetroPrice.success(
        target,
        price,
        Instant.now(),
        "OFFICIAL_API",
        officialMenuUrl(restaurant));
    result.setRestaurantRef(restaurant.slug());
    result.setRestaurantName(restaurant.name());
    result.setAddress(restaurant.address());
    applyAuditMetadata(result, body);
    return Optional.of(result);
  }

  private Optional<OfficialRestaurant> findNearestRestaurant(
      CanesBoxTrackerProperties.MetroTarget target
  ) throws Exception {
    var variables = objectMapper.createObjectNode();
    variables.put("latitude", target.getLatitude());
    variables.put("longitude", target.getLongitude());
    variables.put("radius", properties.getOfficialSearchRadiusMiles());
    variables.put("limit", properties.getOfficialSearchLimit());
    var body = postGraphQl("Restaurants", restaurantsQuery(), variables);
    var restaurants = objectMapper.readTree(body).path("data").path("restaurants");
    if (!restaurants.isArray()) {
      return Optional.empty();
    }
    OfficialRestaurant closestRestaurant = null;
    for (JsonNode restaurant : restaurants) {
      var candidate = productionRestaurantFrom(restaurant);
      if (candidate.isEmpty()) {
        continue;
      }
      if (closestRestaurant == null || candidate.get().distance() < closestRestaurant.distance()) {
        closestRestaurant = candidate.get();
      }
    }
    return Optional.ofNullable(closestRestaurant);
  }

  private Optional<OfficialRestaurant> productionRestaurantFrom(JsonNode restaurant) {
    if (!restaurant.path("supportsOnlineOrdering").asBoolean(true)) {
      return Optional.empty();
    }
    var id = restaurant.path("id").asInt();
    var slug = restaurant.path("slug").asText("");
    var name = restaurant.path("name").asText("Raising Cane's");
    var extRef = restaurant.path("extRef").asText("");
    if (id <= 0 || slug.isBlank() || !extRef.matches("\\d+")) {
      return Optional.empty();
    }
    if (isNonProductionStore(name, slug)) {
      return Optional.empty();
    }
    return Optional.of(new OfficialRestaurant(
        id,
        slug,
        name,
        addressFromRestaurant(restaurant),
        restaurant.path("distance").asDouble(Double.MAX_VALUE)));
  }

  private boolean isNonProductionStore(String name, String slug) {
    var markerSource = normalize(name + " " + slug);
    return markerSource.contains("demo")
        || markerSource.contains("sandbox")
        || markerSource.contains("test");
  }

  private String fetchRestaurantMenu(OfficialRestaurant restaurant) throws Exception {
    var variables = objectMapper.createObjectNode();
    variables.put("id", restaurant.id());
    return postGraphQl("Restaurant", restaurantQuery(), variables);
  }

  private String postGraphQl(String operationName, String query, JsonNode variables) throws Exception {
    var payload = objectMapper.createObjectNode();
    payload.put("query", query);
    payload.set("variables", variables);
    payload.put("operationName", operationName);
    var request = HttpRequest.newBuilder(URI.create(properties.getGraphQlUrl()))
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
        .timeout(properties.getRequestTimeout())
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("Origin", OFFICIAL_ORDER_BASE_URL)
        .header("Referer", OFFICIAL_ORDER_BASE_URL + "/")
        .header("User-Agent", officialUserAgent())
        .header("olo-platform", "web")
        .header("nomnom-platform", "web")
        .header("platform", "web")
        .header("app-version", "prod")
        .build();
    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IllegalStateException(
          "Official GraphQL API returned HTTP " + response.statusCode() + ": " + response.body());
    }
    var body = response.body();
    var root = objectMapper.readTree(body);
    if (root.path("errors").isArray() && !root.path("errors").isEmpty()) {
      throw new IllegalStateException(root.path("errors").get(0).path("message").asText("Official GraphQL API returned an error."));
    }
    return body;
  }

  private CanesBoxMetroPrice fetchRestaurantByRefPrice(
      CanesBoxTrackerProperties.MetroTarget target
  ) throws Exception {
    var request = HttpRequest.newBuilder(restaurantUri(target))
          .GET()
          .timeout(properties.getRequestTimeout())
          .header("Accept", "application/json")
          .header("User-Agent", officialUserAgent())
          .header("Origin", OFFICIAL_ORDER_BASE_URL)
          .header("Referer", OFFICIAL_ORDER_BASE_URL + "/")
          .header("clientid", "raisingcanes")
          .header("ui-transformer", "restaurantByRef")
          .header("ui-cache-ttl", "300")
          .build();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("Official Cane's API returned HTTP " + response.statusCode());
      }
      var body = response.body();
      var price = findBoxComboPrice(body)
          .orElseThrow(() -> new IllegalStateException("The Box Combo price was not found."));
      var result = CanesBoxMetroPrice.success(target, price, Instant.now(), "OFFICIAL_API", restaurantUri(target).toString());
      applyAuditMetadata(result, body);
      return result;
  }

  Optional<BigDecimal> findBoxComboPrice(String body) throws Exception {
    return findBoxComboPrice(objectMapper.readTree(body));
  }

  Optional<BigDecimal> findPublicMenuBoxComboPrice(String body) {
    var marker = normalize(properties.getItemName());
    var normalizedBody = normalize(body);
    var itemIndex = normalizedBody.indexOf(marker);
    if (itemIndex < 0) {
      return Optional.empty();
    }
    var originalIndex = body.toLowerCase(Locale.ROOT).indexOf(properties.getItemName().toLowerCase(Locale.ROOT));
    var searchStart = Math.max(0, originalIndex);
    var searchEnd = Math.min(body.length(), searchStart + 2000);
    var snippet = body.substring(searchStart, searchEnd);
    var matcher = java.util.regex.Pattern
        .compile("(?i)\"(?:price|Price)\"\\s*:\\s*\"?\\$?([0-9]+(?:\\.[0-9]{1,2})?)")
        .matcher(snippet);
    if (matcher.find()) {
      return Optional.of(new BigDecimal(matcher.group(1)).setScale(2));
    }
    return Optional.empty();
  }

  private Optional<BigDecimal> findBoxComboPrice(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return Optional.empty();
    }
    if (node.isObject() && nameMatchesBoxCombo(node)) {
      var price = priceFromNode(node);
      if (price.isPresent()) {
        return price;
      }
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        var price = findBoxComboPrice(child);
        if (price.isPresent()) {
          return price;
        }
      }
    } else if (node.isObject()) {
      Iterator<JsonNode> children = node.elements();
      while (children.hasNext()) {
        var price = findBoxComboPrice(children.next());
        if (price.isPresent()) {
          return price;
        }
      }
    }
    return Optional.empty();
  }

  private boolean nameMatchesBoxCombo(JsonNode node) {
    for (String key : new String[] {"name", "title", "displayName", "label"}) {
      var value = node.path(key).asText("");
      if (normalize(value).equals(normalize(properties.getItemName()))) {
        return true;
      }
    }
    return false;
  }

  private Optional<BigDecimal> priceFromNode(JsonNode node) {
    for (String key : new String[] {"cost", "price", "baseCost", "basePrice"}) {
      var price = decimalValue(node.path(key));
      if (price.isPresent()) {
        return price;
      }
    }
    return Optional.empty();
  }

  private Optional<BigDecimal> decimalValue(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return Optional.empty();
    }
    if (node.isNumber()) {
      return Optional.of(node.decimalValue()).map(this::normalizePrice);
    }
    if (node.isTextual()) {
      var cleaned = node.asText().replace("$", "").trim();
      if (cleaned.matches("\\d+(\\.\\d{1,2})?")) {
        return Optional.of(normalizePrice(new BigDecimal(cleaned)));
      }
    }
    return Optional.empty();
  }

  private BigDecimal normalizePrice(BigDecimal value) {
    return value.compareTo(new BigDecimal("100")) > 0
        ? value.movePointLeft(2).setScale(2)
        : value.setScale(2);
  }

  URI restaurantUri(CanesBoxTrackerProperties.MetroTarget target) {
    var baseUrl = properties.getApiBaseUrl().replaceAll("/+$", "");
    var encodedRef = URLEncoder.encode(target.getRestaurantRef(), StandardCharsets.UTF_8)
        .replace("+", "%20");
    return URI.create(baseUrl + "/restaurants/byref/" + encodedRef);
  }

  private CanesBoxMetroPrice fetchFallbackPrice(
      CanesBoxTrackerProperties.MetroTarget target,
      String officialFailure
  ) {
    if (!properties.isPublicMenuFallbackEnabled()) {
      return CanesBoxMetroPrice.failure(
          target,
          officialFailure + "; public menu fallback is disabled.");
    }
    if (target.getFallbackMenuUrl() == null || target.getFallbackMenuUrl().isBlank()) {
      return CanesBoxMetroPrice.failure(target, officialFailure);
    }
    try {
      var request = HttpRequest.newBuilder(URI.create(target.getFallbackMenuUrl()))
          .GET()
          .timeout(properties.getRequestTimeout())
          .header("Accept", "text/html,application/json")
          .header("User-Agent", "christopherbell.dev raising-canes-box-index")
          .build();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return CanesBoxMetroPrice.failure(
            target,
            officialFailure + "; fallback menu returned HTTP " + response.statusCode());
      }
      var body = responseBody(response);
      var price = findPublicMenuBoxComboPrice(body)
          .orElseThrow(() -> new IllegalStateException("Fallback menu did not contain The Box Combo price."));
      if (price.compareTo(properties.getMinimumPublicMenuPrice()) < 0) {
        return failedFallback(
            target,
            officialFailure + "; fallback menu price was implausibly low: " + price,
            body);
      }
      var result = CanesBoxMetroPrice.success(target, price, Instant.now(), "PUBLIC_MENU", target.getFallbackMenuUrl());
      applyAuditMetadata(result, body);
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return CanesBoxMetroPrice.failure(target, officialFailure + "; fallback menu request was interrupted.");
    } catch (Exception e) {
      return CanesBoxMetroPrice.failure(target, officialFailure + "; fallback menu failed: " + e.getMessage());
    }
  }

  private String responseBody(HttpResponse<byte[]> response) throws Exception {
    var bytes = response.body();
    var encoding = response.headers().firstValue("Content-Encoding").orElse("");
    if ("gzip".equalsIgnoreCase(encoding) || (bytes.length > 2 && bytes[0] == 0x1f && bytes[1] == (byte) 0x8b)) {
      try (var input = new GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private void applyAuditMetadata(CanesBoxMetroPrice result, String body) {
    result.setRawResponseHash(sha256(body));
    result.setMatchedItemName(properties.getItemName());
    result.setSourceFetchedOn(result.getCollectedOn());
  }

  private CanesBoxMetroPrice failedFallback(
      CanesBoxTrackerProperties.MetroTarget target,
      String reason,
      String body
  ) {
    var result = CanesBoxMetroPrice.failure(target, reason);
    result.setSourceName("PUBLIC_MENU");
    result.setSourceUrl(target.getFallbackMenuUrl());
    applyAuditMetadata(result, body);
    return result;
  }

  private String sha256(String value) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to hash Raising Canes Box Index source response.", e);
    }
  }

  private String normalize(String value) {
    return String.valueOf(value)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "");
  }

  private boolean hasCoordinates(CanesBoxTrackerProperties.MetroTarget target) {
    return target.getLatitude() != 0.0 || target.getLongitude() != 0.0;
  }

  private String officialMenuUrl(OfficialRestaurant restaurant) {
    return OFFICIAL_ORDER_BASE_URL + "/location/" + restaurant.slug() + "/menu";
  }

  private String addressFromRestaurant(JsonNode restaurant) {
    var parts = new ArrayList<String>();
    addIfPresent(parts, restaurant.path("streetAddress").asText(""));
    var city = restaurant.path("city").asText("");
    var state = restaurant.path("state").asText("");
    var zip = restaurant.path("zip").asText("");
    var stateZip = List.of(state, zip).stream()
        .filter(value -> value != null && !value.isBlank())
        .toList();
    if (city != null && !city.isBlank() && !stateZip.isEmpty()) {
      parts.add(city + ", " + String.join(" ", stateZip));
    } else if (city != null && !city.isBlank()) {
      parts.add(city);
    } else if (!stateZip.isEmpty()) {
      parts.add(String.join(" ", stateZip));
    }
    return String.join(", ", parts);
  }

  private void addIfPresent(List<String> values, String value) {
    if (value != null && !value.isBlank()) {
      values.add(value);
    }
  }

  private String officialUserAgent() {
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/125 Safari/537.36";
  }

  private String restaurantsQuery() {
    return """
        query Restaurants($latitude: Float, $longitude: Float, $radius: Int, $limit: Int) {
          restaurants(latitude: $latitude, longitude: $longitude, radius: $radius, limit: $limit) {
            id
            slug
            extRef
            name
            streetAddress
            city
            state
            zip
            distance
            supportsOnlineOrdering
          }
        }
        """;
  }

  private String restaurantQuery() {
    return """
        query Restaurant($id: Int, $slug: String) {
          restaurant(id: $id, slug: $slug) {
            id
            slug
            menu {
              categories {
                products {
                  name
                  cost
                }
              }
            }
          }
        }
        """;
  }

  private record OfficialRestaurant(int id, String slug, String name, String address, double distance) {
  }
}

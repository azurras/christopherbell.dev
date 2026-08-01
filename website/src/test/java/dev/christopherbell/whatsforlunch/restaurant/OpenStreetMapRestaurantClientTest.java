package dev.christopherbell.whatsforlunch.restaurant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenStreetMapRestaurantClientTest {
  private static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024 * 1024;
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void parseRestaurants_mapsOpenStreetMapTags() throws Exception {
    var client = client();
    var method = OpenStreetMapRestaurantClient.class.getDeclaredMethod("parseRestaurants", String.class);
    method.setAccessible(true);
    var body = """
        {
          "elements": [
            {
              "type": "node",
              "id": 123,
              "lat": 30.2672,
              "lon": -97.7431,
              "tags": {
                "name": "Austin Lunch",
                "addr:housenumber": "100",
                "addr:street": "Congress Ave",
                "addr:city": "Austin",
                "addr:postcode": "78701",
                "amenity": "restaurant",
                "cuisine": "thai",
                "contact:phone": "512-555-0100",
                "contact:website": "https://example.com"
              }
            },
            {
              "type": "node",
              "id": 456,
              "tags": {
                "amenity": "restaurant"
              }
            }
          ]
        }
        """;

    @SuppressWarnings("unchecked")
    var restaurants = (java.util.List<dev.christopherbell.whatsforlunch.restaurant.model.Restaurant>)
        method.invoke(client, body);

    assertEquals(1, restaurants.size());
    var restaurant = restaurants.getFirst();
    assertEquals("osm:node:123", restaurant.getId());
    assertEquals("Austin Lunch", restaurant.getName());
    assertEquals("100 Congress Ave", restaurant.getAddress().getStreet1());
    assertEquals("Austin", restaurant.getAddress().getCity());
    assertEquals("TX", restaurant.getAddress().getState());
    assertEquals("US", restaurant.getAddress().getCountry());
    assertEquals(30.2672, restaurant.getAddress().getLatitude());
    assertEquals(-97.7431, restaurant.getAddress().getLongitude());
    assertEquals("78701", restaurant.getAddress().getPostalCode());
    assertEquals("thai", restaurant.getCuisine());
    assertEquals("512-555-0100", restaurant.getPhoneNumber());
    assertEquals("restaurant", restaurant.getSourceAmenity());
    assertEquals("https://example.com", restaurant.getWebsite());
  }

  @Test
  void parseRestaurants_defaultsMissingAddressCityToImportedMetro() throws Exception {
    var client = client();
    var method = OpenStreetMapRestaurantClient.class.getDeclaredMethod("parseRestaurants", String.class);
    method.setAccessible(true);
    var body = """
        {
          "elements": [
            {
              "type": "way",
              "id": 789,
              "center": {
                "lat": 30.3001,
                "lon": -97.7002
              },
              "tags": {
                "name": "Metro Lunch"
              }
            }
          ]
        }
        """;

    @SuppressWarnings("unchecked")
    var restaurants = (java.util.List<dev.christopherbell.whatsforlunch.restaurant.model.Restaurant>)
        method.invoke(client, body);

    assertEquals(1, restaurants.size());
    var restaurant = restaurants.getFirst();
    assertEquals("osm:way:789", restaurant.getId());
    assertEquals("Imported Metro", restaurant.getAddress().getCity());
    assertEquals("TX", restaurant.getAddress().getState());
    assertEquals(30.3001, restaurant.getAddress().getLatitude());
    assertEquals(-97.7002, restaurant.getAddress().getLongitude());
    assertNull(restaurant.getAddress().getStreet1());
  }

  @Test
  void parseRestaurants_sortsByNameWithoutFastFoodPenalty() throws Exception {
    var client = client();
    var method = OpenStreetMapRestaurantClient.class.getDeclaredMethod("parseRestaurants", String.class);
    method.setAccessible(true);
    var body = """
        {
          "elements": [
            {
              "type": "node",
              "id": 1,
              "tags": {
                "name": "A Taco Bell",
                "amenity": "fast_food"
              }
            },
            {
              "type": "node",
              "id": 2,
              "tags": {
                "name": "Z Bistro",
                "amenity": "restaurant"
              }
            }
          ]
        }
        """;

    @SuppressWarnings("unchecked")
    var restaurants = (java.util.List<dev.christopherbell.whatsforlunch.restaurant.model.Restaurant>)
        method.invoke(client, body);

    assertEquals(2, restaurants.size());
    assertEquals("A Taco Bell", restaurants.getFirst().getName());
    assertEquals("Z Bistro", restaurants.get(1).getName());
  }

  @Test
  void buildQuery_includesAllConfiguredMetroBoundingBoxes() throws Exception {
    var client = client();
    var method = OpenStreetMapRestaurantClient.class.getDeclaredMethod("buildQuery");
    method.setAccessible(true);

    var query = (String) method.invoke(client);

    assertTrue(query.contains("29.95,-98.25,30.75,-97.15"));
    assertTrue(query.contains("37.2,-122.65,38.2,-121.65"));
    assertTrue(query.contains("29.7,-90.45,30.25,-89.65"));
    assertTrue(query.contains("32.45,-97.35,33.15,-96.35"));
    assertTrue(query.contains("fast_food"));
  }

  @Test
  void getConfiguredMetroRestaurantsAcceptsJsonAtTheExactResponseLimit() throws Exception {
    startServer(200, paddedJson(MAXIMUM_RESPONSE_BYTES));

    var restaurants = client(serverUri()).getConfiguredMetroRestaurants();

    assertTrue(restaurants.isEmpty());
  }

  @Test
  void getConfiguredMetroRestaurantsRejectsJsonOneBytePastTheResponseLimit() throws Exception {
    startServer(200, paddedJson(MAXIMUM_RESPONSE_BYTES + 1));

    assertThrows(
        dev.christopherbell.libs.http.BodyLimitExceededException.class,
        () -> client(serverUri()).getConfiguredMetroRestaurants());
  }

  @Test
  void getConfiguredMetroRestaurantsPreservesMalformedJsonFailure() throws Exception {
    startServer(200, "not-json");

    assertThrows(
        tools.jackson.core.exc.StreamReadException.class,
        () -> client(serverUri()).getConfiguredMetroRestaurants());
  }

  @Test
  void getConfiguredMetroRestaurantsPreservesStatusDiagnosticWithoutResponseBody() throws Exception {
    startServer(503, "upstream secret body");

    var exception = assertThrows(
        IOException.class,
        () -> client(serverUri()).getConfiguredMetroRestaurants());

    assertTrue(exception.getMessage().contains("503"));
    assertTrue(!exception.getMessage().contains("upstream secret body"));
  }

  private OpenStreetMapRestaurantClient client() {
    return client(URI.create("https://example.com"));
  }

  private OpenStreetMapRestaurantClient client(URI endpoint) {
    var properties = new WflProperties();
    properties.getRestaurantImport().getOsm().setEndpoint(endpoint);
    properties.getRestaurantImport().getOsm().setTimeout(java.time.Duration.ofSeconds(25));
    properties.getRestaurantImport().getOsm().setResultLimit(500);
    return new OpenStreetMapRestaurantClient(new ObjectMapper(), properties);
  }

  private void startServer(int status, String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      var bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
  }

  private URI serverUri() {
    return URI.create("http://localhost:" + server.getAddress().getPort());
  }

  private String paddedJson(int byteCount) {
    var json = "{\"elements\":[]}";
    return json + " ".repeat(byteCount - json.length());
  }
}

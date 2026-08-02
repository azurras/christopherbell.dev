package dev.christopherbell.whatsforlunch.restaurant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.testsupport.HeadersThenStallServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
  void parseRestaurants_rejectsMissingLocalityInsteadOfInventingOne() throws Exception {
    var restaurants = parseRestaurants("""
        {
          "elements": [{
            "type": "way",
            "id": 789,
            "center": {"lat": 30.3001, "lon": -97.7002},
            "tags": {"name": "Metro Lunch"}
          }]
        }
        """);

    assertTrue(restaurants.isEmpty());
  }

  @Test
  void parseRestaurants_acceptsSupportedLocalityTagsWithCanonicalCityAndState() throws Exception {
    var restaurants = parseRestaurants("""
        {
          "elements": [
            {"type":"node","id":1,"lat":30.2672,"lon":-97.7431,
             "tags":{"name":"A City","addr:city":"austin"}},
            {"type":"node","id":2,"lat":37.8044,"lon":-122.2712,
             "tags":{"name":"B Town","addr:town":"OAKLAND","addr:state":"CA"}},
            {"type":"node","id":3,"lat":29.9841,"lon":-90.1529,
             "tags":{"name":"C Village","addr:village":"Metairie","addr:country":"USA"}},
            {"type":"node","id":4,"lat":33.0198,"lon":-96.6989,
             "tags":{"name":"D Municipality","addr:municipality":"Plano","addr:country":"United States"}}
          ]
        }
        """);

    assertEquals(java.util.List.of("Austin", "Oakland", "Metairie", "Plano"), restaurants.stream()
        .map(restaurant -> restaurant.getAddress().getCity())
        .toList());
    assertEquals(java.util.List.of("TX", "CA", "LA", "TX"), restaurants.stream()
        .map(restaurant -> restaurant.getAddress().getState())
        .toList());
    assertTrue(restaurants.stream()
        .allMatch(restaurant -> "US".equals(restaurant.getAddress().getCountry())));
  }

  @Test
  void parseRestaurants_disambiguatesSameNamePlacesByCoordinates() throws Exception {
    var restaurants = parseRestaurants("""
        {
          "elements": [
            {"type":"node","id":1,"lat":37.3688,"lon":-122.0363,
             "tags":{"name":"California Sunnyvale","addr:city":"Sunnyvale"}},
            {"type":"node","id":2,"lat":32.7965,"lon":-96.5608,
             "tags":{"name":"Texas Sunnyvale","addr:city":"Sunnyvale"}}
          ]
        }
        """);

    assertEquals(java.util.List.of("CA", "TX"), restaurants.stream()
        .map(restaurant -> restaurant.getAddress().getState())
        .toList());
  }

  @Test
  void parseRestaurants_acceptsExpandedPlacesAndFullStateNames() throws Exception {
    var restaurants = parseRestaurants("""
        {
          "elements": [
            {"type":"node","id":1,"lat":32.7555,"lon":-97.3308,
             "tags":{"name":"Fort Worth Lunch","addr:city":"Fort Worth","addr:state":"Texas"}},
            {"type":"node","id":2,"lat":37.6819,"lon":-121.7680,
             "tags":{"name":"Livermore Lunch","addr:city":"Livermore","addr:state":"California"}},
            {"type":"node","id":3,"lat":29.9511,"lon":-90.0715,
             "tags":{"name":"New Orleans Lunch","addr:city":"New Orleans","addr:state":"Louisiana"}}
          ]
        }
        """);

    assertEquals(java.util.List.of("Fort Worth", "Livermore", "New Orleans"), restaurants.stream()
        .map(restaurant -> restaurant.getAddress().getCity())
        .toList());
    assertEquals(java.util.List.of("TX", "CA", "LA"), restaurants.stream()
        .map(restaurant -> restaurant.getAddress().getState())
        .toList());
  }

  @Test
  void parseRestaurants_rejectsCityOutsideOwningMetroBounds() throws Exception {
    var restaurants = parseRestaurants("""
        {
          "elements": [{"type":"node","id":1,"lat":32.7767,"lon":-96.7970,
            "tags":{"name":"Misplaced Austin","addr:city":"Austin"}}]
        }
        """);

    assertTrue(restaurants.isEmpty());
  }

  @Test
  void parseRestaurants_rejectsUnsupportedContradictoryOrCoordinateLessLocations() throws Exception {
    var restaurants = parseRestaurants("""
        {
          "elements": [
            {"type":"node","id":1,"lat":30.2672,"lon":-97.7431,
             "tags":{"name":"Unsupported","addr:city":"Houston"}},
            {"type":"node","id":2,"lat":30.2672,"lon":-97.7431,
             "tags":{"name":"Wrong State","addr:city":"Austin","addr:state":"CA"}},
            {"type":"node","id":3,"lat":30.2672,"lon":-97.7431,
             "tags":{"name":"Wrong Country","addr:city":"Austin","addr:country":"CA"}},
            {"type":"node","id":4,"lon":-97.7431,
             "tags":{"name":"Missing Latitude","addr:city":"Austin"}},
            {"type":"node","id":5,"lat":30.2672,
             "tags":{"name":"Missing Longitude","addr:city":"Austin"}}
          ]
        }
        """);

    assertTrue(restaurants.isEmpty());
  }

  @Test
  void parseRestaurants_sortsByNameWithoutFastFoodPenalty() throws Exception {
    var restaurants = parseRestaurants("""
        {
          "elements": [
            {"type":"node","id":1,"lat":30.2672,"lon":-97.7431,
             "tags":{"name":"A Taco Bell","amenity":"fast_food","addr:city":"Austin"}},
            {"type":"node","id":2,"lat":30.2673,"lon":-97.7432,
             "tags":{"name":"Z Bistro","amenity":"restaurant","addr:city":"Austin"}}
          ]
        }
        """);

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

  @Test
  void getConfiguredMetroRestaurantsWhenBodyStallsAfterHeadersHonorsRequestTimeout()
      throws Exception {
    try (var stall = new HeadersThenStallServer()) {
      var properties = new WflProperties();
      properties.getRestaurantImport().getOsm().setEndpoint(stall.uri("/overpass"));
      properties.getRestaurantImport().getOsm().setTimeout(Duration.ofMillis(100));
      properties.getRestaurantImport().getOsm().setResultLimit(500);
      var client = new OpenStreetMapRestaurantClient(new ObjectMapper(), properties);

      assertThrows(
          HttpTimeoutException.class,
          () -> stall.callWhileBodyStalls(
              client::getConfiguredMetroRestaurants, Duration.ofSeconds(12)));
    }
  }

  @SuppressWarnings("unchecked")
  private java.util.List<dev.christopherbell.whatsforlunch.restaurant.model.Restaurant>
      parseRestaurants(String body) throws Exception {
    var method = OpenStreetMapRestaurantClient.class.getDeclaredMethod("parseRestaurants", String.class);
    method.setAccessible(true);
    return (java.util.List<dev.christopherbell.whatsforlunch.restaurant.model.Restaurant>)
        method.invoke(client(), body);
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

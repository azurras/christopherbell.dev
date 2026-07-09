package dev.christopherbell.whatsforlunch.restaurant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenStreetMapRestaurantClientTest {

  @Test
  void parseRestaurants_mapsOpenStreetMapTags() throws Exception {
    var client = new OpenStreetMapRestaurantClient(
        new ObjectMapper(),
        "https://example.com",
        "29.95,-98.25,30.75,-97.15",
        25,
        500,
        true);
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
    var client = new OpenStreetMapRestaurantClient(
        new ObjectMapper(),
        "https://example.com",
        "29.95,-98.25,30.75,-97.15",
        25,
        500,
        true);
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
    var client = new OpenStreetMapRestaurantClient(
        new ObjectMapper(),
        "https://example.com",
        "29.95,-98.25,30.75,-97.15",
        25,
        500,
        true);
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
    var client = new OpenStreetMapRestaurantClient(
        new ObjectMapper(),
        "https://example.com",
        "29.95,-98.25,30.75,-97.15;37.20,-122.65,38.20,-121.65;29.70,-90.45,30.25,-89.65;32.45,-97.35,33.15,-96.35",
        25,
        500,
        true);
    var method = OpenStreetMapRestaurantClient.class.getDeclaredMethod("buildQuery");
    method.setAccessible(true);

    var query = (String) method.invoke(client);

    assertTrue(query.contains("29.95,-98.25,30.75,-97.15"));
    assertTrue(query.contains("37.20,-122.65,38.20,-121.65"));
    assertTrue(query.contains("29.70,-90.45,30.25,-89.65"));
    assertTrue(query.contains("32.45,-97.35,33.15,-96.35"));
    assertTrue(query.contains("fast_food"));
  }
}

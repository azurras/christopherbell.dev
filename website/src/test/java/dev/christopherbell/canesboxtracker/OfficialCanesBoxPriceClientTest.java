package dev.christopherbell.canesboxtracker;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.canesboxtracker.model.CanesBoxTrackerProperties;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialCanesBoxPriceClientTest {

  @Test
  void findBoxComboPriceFindsNestedOfficialMenuPrice() throws Exception {
    var properties = new CanesBoxTrackerProperties();
    properties.setItemName("The Box Combo");
    var client = new OfficialCanesBoxPriceClient(new ObjectMapper(), properties);

    var price = client.findBoxComboPrice("""
        {
          "categories": [
            {
              "products": [
                {"name": "Three Finger Combo", "cost": 999},
                {"name": "The Box Combo", "cost": 1299}
              ]
            }
          ]
        }
        """);

    assertTrue(price.isPresent());
    assertEquals(new BigDecimal("12.99"), price.get());
  }

  @Test
  void fetchBoxComboPriceUsesOfficialGraphQlMenuForNearestMetroRestaurant() throws Exception {
    var sawLocationSearch = new AtomicBoolean(false);
    var sawMenuLookup = new AtomicBoolean(false);
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/graphql", exchange -> {
      assertEquals("POST", exchange.getRequestMethod());
      assertEquals("web", exchange.getRequestHeaders().getFirst("olo-platform"));
      assertEquals("web", exchange.getRequestHeaders().getFirst("nomnom-platform"));
      assertEquals("web", exchange.getRequestHeaders().getFirst("platform"));
      assertNotNull(exchange.getRequestHeaders().getFirst("app-version"));

      var requestBody = new String(exchange.getRequestBody().readAllBytes());
      String body;
      if (requestBody.contains("\"operationName\":\"Restaurants\"")) {
        sawLocationSearch.set(true);
        assertTrue(requestBody.contains("\"latitude\":30.2672"));
        assertTrue(requestBody.contains("\"longitude\":-97.7431"));
        body = """
            {
              "data": {
                "restaurants": [
                  {
                    "id": 123993,
                    "slug": "raising-canes-micros-qa-demo-vendor",
                    "extRef": "OLO",
                    "name": "Raising Cane's Micros QA Demo Vendor",
                    "streetAddress": "1 World Trade Center",
                    "city": "New York",
                    "state": "NY",
                    "zip": "10007",
                    "distance": 0.4,
                    "supportsOnlineOrdering": true
                  },
                  {
                    "id": 146171,
                    "slug": "raising-canes-xpient-sandbox-demo-vendor",
                    "extRef": "OLO",
                    "name": "Raising Cane's Xpient Demo Vendor",
                    "streetAddress": "1 World Trade Center",
                    "city": "New York",
                    "state": "NY",
                    "zip": "10006",
                    "distance": 0.4,
                    "supportsOnlineOrdering": true
                  },
                  {
                    "id": 201791,
                    "slug": "raising-canes-888",
                    "extRef": "0888",
                    "name": "Raising Cane's #888",
                    "streetAddress": "1501 Broadway",
                    "city": "New York",
                    "state": "NY",
                    "zip": "10036",
                    "distance": 3.2,
                    "supportsOnlineOrdering": true
                  },
                  {
                    "id": 201800,
                    "slug": "raising-canes-879",
                    "extRef": "0879",
                    "name": "Raising Cane's #879",
                    "streetAddress": "20 Astor Place",
                    "city": "Austin",
                    "state": "TX",
                    "zip": "78701",
                    "distance": 1.4,
                    "supportsOnlineOrdering": true
                  }
                ]
              }
            }
            """;
      } else if (requestBody.contains("\"operationName\":\"Restaurant\"")) {
        sawMenuLookup.set(true);
        assertTrue(requestBody.contains("\"id\":201800"));
        assertTrue(requestBody.contains("cost"));
        assertTrue(!requestBody.contains("baseCost"));
        assertTrue(!requestBody.contains("basePrice"));
        assertTrue(!requestBody.contains("\\n                  price\\n"));
        body = """
            {
              "data": {
                "restaurant": {
                  "id": 201800,
                  "slug": "raising-canes-879",
                  "menu": {
                    "categories": [
                      {
                        "products": [
                          {"name": "The Box Combo", "cost": 1499}
                        ]
                      }
                    ]
                  }
                }
              }
            }
            """;
      } else {
        body = "{\"errors\":[{\"message\":\"unexpected operation\"}]}";
      }
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.getBytes().length);
      exchange.getResponseBody().write(body.getBytes());
      exchange.close();
    });
    server.start();
    try {
      var properties = new CanesBoxTrackerProperties();
      properties.setGraphQlUrl("http://localhost:" + server.getAddress().getPort() + "/graphql");
      properties.setItemName("The Box Combo");
      var target = new CanesBoxTrackerProperties.MetroTarget();
      target.setMetroName("Austin-Round Rock");
      target.setCity("Austin");
      target.setState("TX");
      target.setLatitude(30.2672);
      target.setLongitude(-97.7431);
      target.setRestaurantRef("stale-public-slug");
      var client = new OfficialCanesBoxPriceClient(new ObjectMapper(), properties);

      var result = client.fetchBoxComboPrice(target);

      assertTrue(sawLocationSearch.get());
      assertTrue(sawMenuLookup.get());
      assertEquals("SUCCESS", result.getStatus());
      assertEquals(new BigDecimal("14.99"), result.getPrice());
      assertEquals("OFFICIAL_API", result.getSourceName());
      assertEquals("VERIFIED", result.getQualityStatus());
      assertEquals("raising-canes-879", result.getRestaurantRef());
      assertEquals("Raising Cane's #879", result.getRestaurantName());
      assertEquals("20 Astor Place, Austin, TX 78701", result.getAddress());
      assertEquals("https://order.raisingcanes.com/location/raising-canes-879/menu", result.getSourceUrl());
      assertNotNull(result.getRawResponseHash());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void restaurantUriEncodesRefsWithPathSeparators() {
    var properties = new CanesBoxTrackerProperties();
    properties.setApiBaseUrl("https://nomnom-prod-api.raisingcanes.com/");
    var target = new CanesBoxTrackerProperties.MetroTarget();
    target.setRestaurantRef("tx/austin/415-w-martin-luther-king-jr-blvd");
    var client = new OfficialCanesBoxPriceClient(new ObjectMapper(), properties);

    var uri = client.restaurantUri(target);

    assertEquals(
        "https://nomnom-prod-api.raisingcanes.com/restaurants/byref/tx%2Faustin%2F415-w-martin-luther-king-jr-blvd",
        uri.toString());
  }

  @Test
  void fetchBoxComboPriceFallsBackToPublicMenuWhenOfficialApiIsBlocked() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/restaurants/byref/raising-canes-286", exchange -> {
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
    });
    server.createContext("/allmenus", exchange -> {
      var body = """
          {
            "@type": "MenuItem",
            "name": "The Box Combo",
            "offers": [
              {"@type": "Offer", "Price": "11.49", "priceCurrency": "USD"}
            ]
          }
          """;
      exchange.getResponseHeaders().add("Content-Type", "text/html");
      exchange.sendResponseHeaders(200, body.getBytes().length);
      exchange.getResponseBody().write(body.getBytes());
      exchange.close();
    });
    server.start();
    try {
      var properties = new CanesBoxTrackerProperties();
      properties.setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
      properties.setPublicMenuFallbackEnabled(true);
      var target = new CanesBoxTrackerProperties.MetroTarget();
      target.setMetroName("Dallas-Fort Worth-Arlington");
      target.setRestaurantRef("raising-canes-286");
      target.setFallbackMenuUrl("http://localhost:" + server.getAddress().getPort() + "/allmenus");
      var client = new OfficialCanesBoxPriceClient(new ObjectMapper(), properties);

      var result = client.fetchBoxComboPrice(target);

      assertEquals("SUCCESS", result.getStatus());
      assertEquals(new BigDecimal("11.49"), result.getPrice());
      assertEquals("PUBLIC_MENU", result.getSourceName());
      assertEquals(target.getFallbackMenuUrl(), result.getSourceUrl());
      assertEquals("PROVISIONAL", result.getQualityStatus());
      assertNotNull(result.getRawResponseHash());
      assertEquals("The Box Combo", result.getMatchedItemName());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void fetchBoxComboPriceDoesNotUsePublicMenuFallbackByDefault() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/restaurants/byref/raising-canes-286", exchange -> {
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
    });
    server.createContext("/allmenus", exchange -> {
      var body = """
          {
            "@type": "MenuItem",
            "name": "The Box Combo",
            "offers": [
              {"@type": "Offer", "Price": "14.99", "priceCurrency": "USD"}
            ]
          }
          """;
      exchange.sendResponseHeaders(200, body.getBytes().length);
      exchange.getResponseBody().write(body.getBytes());
      exchange.close();
    });
    server.start();
    try {
      var properties = new CanesBoxTrackerProperties();
      properties.setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
      var target = new CanesBoxTrackerProperties.MetroTarget();
      target.setMetroName("Dallas-Fort Worth-Arlington");
      target.setRestaurantRef("raising-canes-286");
      target.setFallbackMenuUrl("http://localhost:" + server.getAddress().getPort() + "/allmenus");
      var client = new OfficialCanesBoxPriceClient(new ObjectMapper(), properties);

      var result = client.fetchBoxComboPrice(target);

      assertEquals("FAILED", result.getStatus());
      assertEquals("EXCLUDED", result.getQualityStatus());
      assertNull(result.getPrice());
      assertTrue(result.getFailureReason().contains("Official Cane's API returned HTTP 403"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void fetchBoxComboPriceRejectsImplausiblyLowPublicMenuFallbackPrice() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/restaurants/byref/raising-canes-286", exchange -> {
      exchange.sendResponseHeaders(403, -1);
      exchange.close();
    });
    server.createContext("/stale-allmenus", exchange -> {
      var body = """
          {
            "@type": "MenuItem",
            "name": "The Box Combo",
            "offers": [
              {"@type": "Offer", "Price": "7.80", "priceCurrency": "USD"}
            ]
          }
          """;
      exchange.getResponseHeaders().add("Content-Type", "text/html");
      exchange.sendResponseHeaders(200, body.getBytes().length);
      exchange.getResponseBody().write(body.getBytes());
      exchange.close();
    });
    server.start();
    try {
      var properties = new CanesBoxTrackerProperties();
      properties.setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
      properties.setMinimumPublicMenuPrice(new BigDecimal("10.00"));
      properties.setPublicMenuFallbackEnabled(true);
      var target = new CanesBoxTrackerProperties.MetroTarget();
      target.setMetroName("Dallas-Fort Worth-Arlington");
      target.setRestaurantRef("raising-canes-286");
      target.setFallbackMenuUrl("http://localhost:" + server.getAddress().getPort() + "/stale-allmenus");
      var client = new OfficialCanesBoxPriceClient(new ObjectMapper(), properties);

      var result = client.fetchBoxComboPrice(target);

      assertEquals("FAILED", result.getStatus());
      assertEquals("EXCLUDED", result.getQualityStatus());
      assertNull(result.getPrice());
      assertTrue(result.getFailureReason().contains("implausibly low"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void fetchBoxComboPriceStoresOfficialAuditMetadata() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/restaurants/byref/raising-canes-101", exchange -> {
      var body = """
          {
            "products": [
              {"name": "The Box Combo", "cost": 1299}
            ]
          }
          """;
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.getBytes().length);
      exchange.getResponseBody().write(body.getBytes());
      exchange.close();
    });
    server.start();
    try {
      var properties = new CanesBoxTrackerProperties();
      properties.setApiBaseUrl("http://localhost:" + server.getAddress().getPort());
      properties.setItemName("The Box Combo");
      var target = new CanesBoxTrackerProperties.MetroTarget();
      target.setMetroName("Austin-Round Rock");
      target.setRestaurantRef("raising-canes-101");
      var client = new OfficialCanesBoxPriceClient(new ObjectMapper(), properties);

      var result = client.fetchBoxComboPrice(target);

      assertEquals("SUCCESS", result.getStatus());
      assertEquals(new BigDecimal("12.99"), result.getPrice());
      assertEquals("OFFICIAL_API", result.getSourceName());
      assertEquals("VERIFIED", result.getQualityStatus());
      assertNotNull(result.getRawResponseHash());
      assertEquals("The Box Combo", result.getMatchedItemName());
      assertNotNull(result.getSourceFetchedOn());
    } finally {
      server.stop(0);
    }
  }
}

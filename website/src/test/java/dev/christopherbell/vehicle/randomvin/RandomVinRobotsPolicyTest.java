package dev.christopherbell.vehicle.randomvin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.randomvin.policy.RandomVinRobotsPolicy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RandomVinRobotsPolicy}.
 */
@DisplayName("RandomVinRobotsPolicy unit tests")
public class RandomVinRobotsPolicyTest {
  private static final int MAXIMUM_RESPONSE_BYTES = 256 * 1024;
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("Allows RandomVIN current comment-only robots file")
  public void testIsAllowed_whenRobotsHasNoUserAgentDirectives_returnsTrue() {
    var policy = new RandomVinRobotsPolicy(vehicleProperties());
    var robotsText = """
        # As a condition of accessing this website, you agree to abide by the following
        # content signals:
        # search: building a search index and providing search results
        # ai-input: inputting content into one or more AI models
        # ai-train: training or fine-tuning AI models.
        """;

    var result = policy.evaluate(robotsText);

    assertTrue(result.allowed());
    assertEquals("no_user_agent_rules", result.reason());
    assertTrue(result.failClosed());
  }

  @Test
  @DisplayName("Disallows getvin path when wildcard user-agent disallows it")
  public void testIsAllowed_whenGetVinIsDisallowed_returnsFalse() {
    var policy = new RandomVinRobotsPolicy(vehicleProperties());
    var robotsText = """
        User-agent: *
        Disallow: /getvin.php
        """;

    var result = policy.evaluate(robotsText);

    assertFalse(result.allowed());
    assertEquals("matching_disallow", result.reason());
    assertTrue(result.failClosed());
  }

  @Test
  @DisplayName("Allows getvin path when a more specific allow rule wins")
  public void testIsAllowed_whenMoreSpecificAllowMatches_returnsTrue() {
    var policy = new RandomVinRobotsPolicy(vehicleProperties());
    var robotsText = """
        User-agent: *
        Disallow: /
        Allow: /getvin.php
        """;

    var result = policy.evaluate(robotsText);

    assertTrue(result.allowed());
    assertEquals("matching_allow", result.reason());
    assertTrue(result.failClosed());
  }

  @Test
  @DisplayName("Allows getvin path when no applicable wildcard rule matches")
  public void testIsAllowed_whenNoRuleMatches_returnsTrue() {
    var policy = new RandomVinRobotsPolicy(vehicleProperties());
    var robotsText = """
        User-agent: *
        Disallow: /admin
        """;

    var result = policy.evaluate(robotsText);

    assertTrue(result.allowed());
    assertEquals("no_matching_disallow", result.reason());
    assertTrue(result.failClosed());
  }

  @Test
  @DisplayName("Allows a robots response at the exact byte limit")
  void evaluate_whenRobotsResponseIsAtLimit_returnsParsedDecision() throws Exception {
    startServer(200, "#".repeat(MAXIMUM_RESPONSE_BYTES));
    var policy = new RandomVinRobotsPolicy(vehicleProperties(serverUrl()));

    var result = policy.evaluate();

    assertTrue(result.allowed());
    assertEquals("no_user_agent_rules", result.reason());
  }

  @Test
  @DisplayName("Fails closed when a robots response exceeds the byte limit")
  void evaluate_whenRobotsResponseExceedsLimit_returnsFetchFailure() throws Exception {
    startServer(200, "#".repeat(MAXIMUM_RESPONSE_BYTES + 1));
    var policy = new RandomVinRobotsPolicy(vehicleProperties(serverUrl()));

    var result = policy.evaluate();

    assertFalse(result.allowed());
    assertEquals("robots_fetch_failed", result.reason());
  }

  private VehicleProperties vehicleProperties() {
    return vehicleProperties("https://randomvin.com/robots.txt");
  }

  private VehicleProperties vehicleProperties(String robotsUrl) {
    var properties = new VehicleProperties();
    properties.getRandomVin().setConnectTimeout(Duration.ofSeconds(10));
    properties.getRandomVin().setRequestTimeout(Duration.ofSeconds(15));
    properties.getRandomVin().setRobotsFailClosed(true);
    properties.getRandomVin().setRobotsUrl(robotsUrl);
    properties.getRandomVin().setPath("/getvin.php");
    properties.getRandomVin().setUserAgent("christopherbell.dev vehicle data collector");
    return properties;
  }

  private void startServer(int status, String responseBody) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/robots.txt", exchange -> {
      var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
  }

  private String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort() + "/robots.txt";
  }
}

package dev.christopherbell.vehicle.randomvin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.randomvin.policy.RandomVinRobotsPolicy;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RandomVinRobotsPolicy}.
 */
@DisplayName("RandomVinRobotsPolicy unit tests")
public class RandomVinRobotsPolicyTest {
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

  private VehicleProperties vehicleProperties() {
    var properties = new VehicleProperties();
    properties.getRandomVin().setConnectTimeout(Duration.ofSeconds(10));
    properties.getRandomVin().setRequestTimeout(Duration.ofSeconds(15));
    properties.getRandomVin().setRobotsFailClosed(true);
    properties.getRandomVin().setRobotsUrl("https://randomvin.com/robots.txt");
    properties.getRandomVin().setPath("/getvin.php");
    properties.getRandomVin().setUserAgent("christopherbell.dev vehicle data collector");
    return properties;
  }
}

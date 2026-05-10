package dev.christopherbell.vehicle.randomvin;

import dev.christopherbell.vehicle.model.VehicleProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Checks RandomVIN robots.txt before collecting generated VINs.
 */
@Component
public class RandomVinRobotsPolicy {
  private final boolean failClosed;
  private final HttpClient httpClient;
  private final String robotsUrl;
  private final Duration requestTimeout;
  private final String targetPath;
  private final String userAgent;

  /**
   * Creates a RandomVIN robots.txt policy checker.
   *
   * @param vehicleProperties vehicle data collection configuration
   */
  public RandomVinRobotsPolicy(
      VehicleProperties vehicleProperties
  ) {
    var properties = vehicleProperties.getRandomVin();
    this.failClosed = properties.isRobotsFailClosed();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .build();
    this.robotsUrl = properties.getRobotsUrl();
    this.requestTimeout = properties.getRequestTimeout();
    this.targetPath = properties.getPath();
    this.userAgent = properties.getUserAgent();
  }

  /**
   * Fetches and evaluates the current RandomVIN robots.txt policy.
   *
   * @return the policy decision for collecting from RandomVIN
   */
  public Result evaluate() {
    var request = HttpRequest.newBuilder(URI.create(robotsUrl))
        .GET()
        .timeout(requestTimeout)
        .header("Accept", "text/plain")
        .header("User-Agent", userAgent)
        .build();

    try {
      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return Result.denied("robots_fetch_status_" + response.statusCode(), failClosed);
      }
      return evaluate(response.body());
    } catch (IOException e) {
      return Result.denied("robots_fetch_failed", failClosed);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Result.denied("robots_fetch_interrupted", failClosed);
    }
  }

  /**
   * Evaluates a robots.txt body against the configured target path and user agent.
   *
   * @param robotsText the robots.txt content to parse
   * @return the policy decision for the configured target path
   */
  Result evaluate(String robotsText) {
    var groups = parseGroups(robotsText);
    if (groups.isEmpty()) {
      return Result.allowed("no_user_agent_rules", failClosed);
    }

    var applicableGroups = groups.stream()
        .filter(RobotsGroup::appliesTo)
        .toList();

    if (applicableGroups.isEmpty()) {
      return Result.allowed("no_applicable_user_agent", failClosed);
    }

    var specificGroups = applicableGroups.stream()
        .filter(group -> !group.appliesToAnyUserAgent())
        .toList();
    var selectedGroups = specificGroups.isEmpty() ? applicableGroups : specificGroups;

    var applicableRules = selectedGroups.stream()
        .flatMap(group -> group.rules.stream())
        .filter(rule -> !rule.path.isBlank())
        .filter(rule -> targetPath.startsWith(rule.path))
        .toList();

    if (applicableRules.isEmpty()) {
      return Result.allowed("no_matching_disallow", failClosed);
    }

    var longestRule = applicableRules.stream()
        .max(Comparator
            .comparingInt((RobotsRule rule) -> rule.path.length())
            .thenComparing(rule -> rule.allow))
        .orElseThrow();

    if (longestRule.allow) {
      return Result.allowed("matching_allow", failClosed);
    }

    return Result.denied("matching_disallow", failClosed);
  }

  /**
   * Parses standard robots.txt user-agent groups and allow/disallow rules.
   *
   * @param robotsText the robots.txt content to parse
   * @return parsed robots groups
   */
  private List<RobotsGroup> parseGroups(String robotsText) {
    var groups = new ArrayList<RobotsGroup>();
    var currentGroup = new RobotsGroup();
    var hasCurrentGroup = false;

    if (robotsText == null || robotsText.isBlank()) {
      return groups;
    }

    for (var rawLine : robotsText.split("\\R")) {
      var line = stripComment(rawLine).trim();
      if (line.isBlank()) {
        continue;
      }

      var separatorIndex = line.indexOf(':');
      if (separatorIndex < 0) {
        continue;
      }

      var key = line.substring(0, separatorIndex).trim().toLowerCase();
      var value = line.substring(separatorIndex + 1).trim();

      if ("user-agent".equals(key)) {
        if (hasCurrentGroup && !currentGroup.rules.isEmpty()) {
          groups.add(currentGroup);
          currentGroup = new RobotsGroup();
        }
        hasCurrentGroup = true;
        currentGroup.userAgents.add(value.toLowerCase(Locale.ROOT));
      } else if (hasCurrentGroup && ("allow".equals(key) || "disallow".equals(key))) {
        currentGroup.rules.add(new RobotsRule("allow".equals(key), value));
      }
    }

    if (hasCurrentGroup) {
      groups.add(currentGroup);
    }

    return groups;
  }

  /**
   * Removes any robots.txt comment from a line.
   *
   * @param line the raw robots.txt line
   * @return the line without a trailing comment
   */
  private String stripComment(String line) {
    var commentIndex = line.indexOf('#');
    if (commentIndex < 0) {
      return line;
    }
    return line.substring(0, commentIndex);
  }

  private class RobotsGroup {
    private final List<String> userAgents = new ArrayList<>();
    private final List<RobotsRule> rules = new ArrayList<>();

    /**
     * Determines whether this group applies to all crawlers.
     *
     * @return true when the group has a wildcard user-agent
     */
    private boolean appliesToAnyUserAgent() {
      return userAgents.contains("*");
    }

    /**
     * Determines whether this group applies to the configured RandomVIN user agent.
     *
     * @return true when this group should be considered for the configured user agent
     */
    private boolean appliesTo() {
      var normalizedUserAgent = userAgent.toLowerCase(Locale.ROOT);
      return userAgents.stream()
          .anyMatch(agent -> "*".equals(agent) || normalizedUserAgent.contains(agent));
    }
  }

  /**
   * A parsed robots.txt allow or disallow rule.
   *
   * @param allow whether the rule allows the path
   * @param path the robots.txt path prefix
   */
  private record RobotsRule(boolean allow, String path) {}

  /**
   * A RandomVIN robots.txt policy decision.
   *
   * @param allowed whether RandomVIN collection is allowed
   * @param reason the reason code for the decision
   * @param failClosed whether unreadable policy checks deny collection by default
   */
  public record Result(boolean allowed, String reason, boolean failClosed) {
    /**
     * Creates an allowed policy result.
     *
     * @param reason the reason code for the allowed decision
     * @return an allowed policy result
     */
    private static Result allowed(String reason, boolean failClosed) {
      return new Result(true, reason, failClosed);
    }

    /**
     * Creates a denied policy result.
     *
     * @param reason the reason code for the denied decision
     * @return a denied policy result
     */
    private static Result denied(String reason, boolean failClosed) {
      return new Result(false, reason, failClosed);
    }
  }
}

package dev.christopherbell.configuration;

import dev.christopherbell.libs.api.APIVersion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for endpoint-aware global rate limits.
 */
@ConfigurationProperties(prefix = "rate-limit")
@Data
public class RateLimitProperties {
  private List<Rule> rules = defaultRules();

  private static List<Rule> defaultRules() {
    var rules = new ArrayList<Rule>();
    rules.add(new Rule(
        "auth-mutations",
        20,
        Duration.ofMinutes(1),
        List.of("POST"),
        List.of(
            "/api/accounts" + APIVersion.V20241215 + "/login",
            "/api/accounts" + APIVersion.V20241215 + "/create",
            "/api/accounts" + APIVersion.V20241215 + "/password-reset/request",
            "/api/accounts" + APIVersion.V20241215 + "/password-reset/confirm")));
    rules.add(new Rule(
        "public-vin-decode",
        60,
        Duration.ofMinutes(1),
        List.of("POST"),
        List.of("/api/vehicles" + APIVersion.V20260509 + "/vin/decode")));
    rules.add(new Rule(
        "shared-folder-uploads",
        120,
        Duration.ofMinutes(1),
        List.of("POST", "PUT", "DELETE"),
        List.of("/api/shared-folder" + APIVersion.V20260717 + "/uploads/**")));
    rules.add(new Rule(
        "shared-folder-mutations",
        120,
        Duration.ofMinutes(1),
        List.of("POST", "PATCH", "DELETE"),
        List.of(
            "/api/shared-folder" + APIVersion.V20260717 + "/folders",
            "/api/shared-folder" + APIVersion.V20260717 + "/entries/**")));
    rules.add(new Rule(
        "shared-folder-transcode",
        30,
        Duration.ofMinutes(1),
        List.of("POST", "DELETE"),
        List.of("/api/shared-folder" + APIVersion.V20260717 + "/transcode/**")));
    rules.add(new Rule(
        "api-mutations",
        300,
        Duration.ofMinutes(1),
        List.of("POST", "PUT", "PATCH", "DELETE"),
        List.of("/api/**")));
    rules.add(new Rule(
        "static-assets",
        10_000,
        Duration.ofMinutes(1),
        List.of("GET"),
        List.of("/css/**", "/images/**", "/js/**", "/favicon.ico")));
    rules.add(new Rule("default", 10_000, Duration.ofMinutes(1), List.of(), List.of("/**")));
    return rules;
  }

  /**
   * Ordered endpoint rate limit rule. The first matching rule is applied.
   */
  @Data
  public static class Rule {
    private String name = "default";
    private long capacity = 10_000;
    private Duration window = Duration.ofMinutes(1);
    private List<String> methods = new ArrayList<>();
    private List<String> paths = new ArrayList<>(List.of("/**"));

    public Rule() {}

    public Rule(String name, long capacity, Duration window, List<String> methods, List<String> paths) {
      this.name = name;
      this.capacity = capacity;
      this.window = window;
      this.methods = methods;
      this.paths = paths;
    }
  }
}

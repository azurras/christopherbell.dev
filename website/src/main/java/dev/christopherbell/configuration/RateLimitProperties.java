package dev.christopherbell.configuration;

import dev.christopherbell.libs.api.APIVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for endpoint-aware global rate limits.
 */
@ConfigurationProperties(prefix = "rate-limit")
@Validated
@Data
public class RateLimitProperties {
  @Min(1)
  private int maxBuckets = 10_000;

  @Valid
  @NotNull
  private List<@NotNull @Valid Rule> rules = defaultRules();

  @AssertTrue(message = "rate limit rule names must be unique")
  public boolean isRuleNamesUnique() {
    if (rules == null) {
      return true;
    }
    var names = new HashSet<String>();
    for (Rule rule : rules) {
      if (rule == null || rule.getName() == null || rule.getName().isBlank()) {
        continue;
      }
      if (!names.add(rule.getName().toLowerCase(Locale.ROOT))) {
        return false;
      }
    }
    return true;
  }

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
        "shared-upload",
        240,
        Duration.ofMinutes(1),
        List.of("POST", "PUT", "PATCH"),
        List.of("/api/shared-folder" + APIVersion.V20260717 + "/uploads/**")));
    rules.add(new Rule(
        "shared-mutation",
        60,
        Duration.ofMinutes(1),
        List.of("POST", "PUT", "PATCH", "DELETE"),
        List.of(
            "/api/shared-folder" + APIVersion.V20260717 + "/mutations/**",
            "/api/shared-folder" + APIVersion.V20260717 + "/recycle/**",
            "/api/shared-folder" + APIVersion.V20260717 + "/folders",
            "/api/shared-folder" + APIVersion.V20260717 + "/entries",
            "/api/shared-folder" + APIVersion.V20260717 + "/entries/**",
            "/api/shared-folder" + APIVersion.V20260717 + "/admin/recycle/**")));
    rules.add(new Rule(
        "shared-transcode",
        10,
        Duration.ofMinutes(1),
        List.of("POST"),
        List.of(
            "/api/shared-folder" + APIVersion.V20260717 + "/media/jobs",
            "/api/shared-folder" + APIVersion.V20260717 + "/media/fallback")));
    rules.add(new Rule(
        "music-metadata-mutation",
        10,
        Duration.ofMinutes(1),
        List.of("POST", "PATCH"),
        List.of(
            "/api/music" + APIVersion.V20260728 + "/tracks/*/metadata",
            "/api/music" + APIVersion.V20260728 + "/metadata-edits/*/undo")));
    rules.add(new Rule(
        "music-library-mutation",
        120,
        Duration.ofMinutes(1),
        List.of("POST", "PUT", "PATCH", "DELETE"),
        List.of(
            "/api/music" + APIVersion.V20260728 + "/library/**",
            "/api/music" + APIVersion.V20260728 + "/queue",
            "/api/music" + APIVersion.V20260728 + "/queue/**")));
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
        List.of("/css/**", "/images/**", "/js/**", "/vendor/**", "/favicon.ico")));
    rules.add(new Rule("default", 10_000, Duration.ofMinutes(1), List.of(), List.of("/**")));
    return rules;
  }

  /**
   * Ordered endpoint rate limit rule. The first matching rule is applied.
   */
  @Data
  public static class Rule {
    @NotBlank
    private String name = "default";
    @Min(1)
    private long capacity = 10_000;
    @NotNull
    @DurationMin(millis = 1)
    private Duration window = Duration.ofMinutes(1);
    @NotNull
    private List<String> methods = new ArrayList<>();
    @NotNull
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

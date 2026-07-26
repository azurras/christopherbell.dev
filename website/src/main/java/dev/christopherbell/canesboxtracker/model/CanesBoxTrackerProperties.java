package dev.christopherbell.canesboxtracker.model;

import java.time.Duration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the Raising Canes Box Index collector.
 *
 * <p>The selected metros and restaurant refs live in configuration so the
 * source list can be corrected without changing collection logic.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "canes-box-tracker")
@Validated
@Data
public class CanesBoxTrackerProperties {
  private boolean enabled = true;
  private String itemName = "The Box Combo";
  private String apiBaseUrl = "https://nomnom-prod-api.raisingcanes.com";
  private String graphQlUrl = "https://gateway.raisingcanes.com/v2/api/v1";
  private int officialSearchRadiusMiles = 25;
  private int officialSearchLimit = 5;
  private boolean publicMenuFallbackEnabled = false;
  private BigDecimal minimumPublicMenuPrice = new BigDecimal("10.00");
  @NotNull @DurationMin(seconds = 1)
  private Duration connectTimeout = Duration.ofSeconds(10);
  @NotNull @DurationMin(seconds = 1)
  private Duration requestTimeout = Duration.ofSeconds(20);
  @NotNull @DurationMin(seconds = 30)
  private Duration leaseDuration = Duration.ofMinutes(2);
  private CollectionSchedule collection = new CollectionSchedule();
  private List<MetroTarget> metros = new ArrayList<>();

  @AssertTrue
  public boolean isLeaseLongerThanRequest() {
    return leaseDuration == null || requestTimeout == null || leaseDuration.compareTo(requestTimeout) > 0;
  }

  /**
   * Weekly collection schedule metadata used by the service.
   */
  @Data
  public static class CollectionSchedule {
    private String zone = "America/Chicago";
  }

  /**
   * One metro/store target used for a weekly price sample.
   */
  @Data
  public static class MetroTarget {
    private String metroName;
    private String city;
    private String state;
    private double latitude;
    private double longitude;
    private String restaurantRef;
    private String restaurantName;
    private String address;
    private String sourceUrl;
    private String fallbackMenuUrl;
  }
}

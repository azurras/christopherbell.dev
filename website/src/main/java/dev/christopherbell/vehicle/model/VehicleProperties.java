package dev.christopherbell.vehicle.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for vehicle data collection clients and scheduled jobs.
 */
@Configuration
@ConfigurationProperties(prefix = "vehicles")
@Validated
@Data
public class VehicleProperties {
  @Valid
  private NhtsaVin nhtsaVin = new NhtsaVin();
  @Valid
  private RandomVin randomVin = new RandomVin();
  @Valid
  private VinDecoder vinDecoder = new VinDecoder();

  @Data
  public static class NhtsaVin {
    private boolean enabled;
    @NotBlank
    private String url;
    @NotNull @DurationMin(seconds = 1)
    private Duration connectTimeout;
    @NotNull @DurationMin(seconds = 1)
    private Duration requestTimeout;
    @NotNull @DurationMin(seconds = 1)
    private Duration cooldown;
    @Min(1) @Max(50)
    private int batchSize = 50;
    @Min(1) @Max(50)
    private int maxBatchSize = 50;
    @NotNull @DurationMin(seconds = 1)
    private Duration initialDelay = Duration.ofMinutes(1);
    @NotNull @DurationMin(seconds = 1)
    private Duration fixedDelay = Duration.ofHours(1);
    @NotNull @DurationMin(seconds = 30)
    private Duration leaseDuration = Duration.ofMinutes(2);
    @NotBlank
    private String stateId;
    @NotBlank
    private String stateNote;

    @AssertTrue
    public boolean isLeaseLongerThanRequest() {
      return leaseDuration == null || requestTimeout == null || leaseDuration.compareTo(requestTimeout) > 0;
    }
  }

  @Data
  public static class RandomVin {
    private boolean enabled;
    @NotBlank
    private String url;
    @NotBlank
    private String robotsUrl;
    @NotBlank
    private String path;
    @NotBlank
    private String userAgent;
    @NotNull @DurationMin(seconds = 1)
    private Duration connectTimeout;
    @NotNull @DurationMin(seconds = 1)
    private Duration requestTimeout;
    @NotNull @DurationMin(seconds = 1)
    private Duration cooldown;
    @NotNull @DurationMin(seconds = 1)
    private Duration initialDelay = Duration.ofMinutes(1);
    @NotNull @DurationMin(seconds = 1)
    private Duration fixedDelay = Duration.ofMinutes(10);
    @NotNull @DurationMin(seconds = 1)
    private Duration minimumSafeDelay = Duration.ofMinutes(1);
    @NotNull @DurationMin(seconds = 30)
    private Duration leaseDuration = Duration.ofMinutes(2);
    @Min(1)
    private int maxCallsPerDay = 144;
    private boolean robotsFailClosed;
    @NotBlank
    private String stateId;
    @NotBlank
    private String stateNote;
    @NotBlank
    private String legacyImportNote;

    @AssertTrue
    public boolean isScheduleSafeWhenEnabled() {
      return !enabled
          || fixedDelay == null
          || minimumSafeDelay == null
          || fixedDelay.compareTo(minimumSafeDelay) >= 0;
    }

    @AssertTrue
    public boolean isLeaseLongerThanRequest() {
      return leaseDuration == null || requestTimeout == null || leaseDuration.compareTo(requestTimeout) > 0;
    }
  }

  @Data
  public static class VinDecoder {
    @Min(1)
    private int rateLimitCapacity = 20;
    @NotNull @DurationMin(seconds = 1)
    private Duration rateLimitWindow;
    @NotBlank
    private String decoderVersion = "vpic-2026-07";
    @NotNull @DurationMin(seconds = 1)
    private Duration cacheTtl = Duration.ofDays(30);
    @Min(1) @Max(50)
    private int maxBatchSize = 20;
  }
}

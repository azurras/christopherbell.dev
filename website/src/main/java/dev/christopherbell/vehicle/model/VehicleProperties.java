package dev.christopherbell.vehicle.model;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for vehicle data collection clients and scheduled jobs.
 */
@Configuration
@ConfigurationProperties(prefix = "vehicles")
@Data
public class VehicleProperties {
  private NhtsaVin nhtsaVin = new NhtsaVin();
  private RandomVin randomVin = new RandomVin();
  private VinDecoder vinDecoder = new VinDecoder();

  @Data
  public static class NhtsaVin {
    private boolean enabled;
    private String url;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration cooldown;
    private int batchSize;
    private int maxBatchSize;
    private String stateId;
    private String stateNote;
  }

  @Data
  public static class RandomVin {
    private boolean enabled;
    private String url;
    private String robotsUrl;
    private String path;
    private String userAgent;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration cooldown;
    private int maxCallsPerDay;
    private boolean robotsFailClosed;
    private String stateId;
    private String stateNote;
    private String legacyImportNote;
  }

  @Data
  public static class VinDecoder {
    private int rateLimitCapacity;
    private Duration rateLimitWindow;
  }
}

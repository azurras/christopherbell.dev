package dev.christopherbell.whatsforlunch.restaurant.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import lombok.Data;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed startup-validated configuration for What's For Lunch. */
@ConfigurationProperties("wfl")
@Validated
@Data
public class WflProperties {
  @Valid
  @NotNull
  private RestaurantOfTheDay restaurantOfTheDay = new RestaurantOfTheDay();

  @Valid
  @NotNull
  private RestaurantImport restaurantImport = new RestaurantImport();

  @Valid
  @NotNull
  private Sessions sessions = new Sessions();

  /** Bounded shared-session lifecycle settings. */
  @Data
  public static class Sessions {
    @Min(1)
    @Max(100)
    private int maxMembers = 20;

    @NotNull
    @DurationMin(hours = 1)
    private Duration activeLifetime = Duration.ofHours(24);

    @NotNull
    @DurationMin(hours = 1)
    private Duration archiveLifetime = Duration.ofDays(30);
  }

  /** Daily-pick scheduler and coverage settings. */
  @Data
  public static class RestaurantOfTheDay {
    private boolean enabled;

    @NotBlank
    private String cron = "0 0 0 * * *";

    @NotBlank
    private String zone = "America/Chicago";

    @Min(1)
    @Max(20)
    private int pickCount = 3;
  }

  /** Remote import, lease, and preview settings. */
  @Data
  public static class RestaurantImport {
    @Valid
    @NotNull
    private Monthly monthly = new Monthly();

    @Valid
    @NotNull
    private Osm osm = new Osm();

    @NotNull
    @DurationMin(seconds = 30)
    private Duration leaseDuration = Duration.ofMinutes(2);

    @NotNull
    @DurationMin(seconds = 30)
    private Duration previewTtl = Duration.ofMinutes(15);

    @AssertTrue(message = "lease duration must exceed the remote timeout by at least 20 seconds")
    public boolean isLeaseCoversRemoteRequest() {
      return leaseDuration == null
          || osm == null
          || osm.getTimeout() == null
          || leaseDuration.compareTo(osm.getTimeout().plusSeconds(20)) >= 0;
    }
  }

  /** Monthly scheduler settings. */
  @Data
  public static class Monthly {
    private boolean enabled = true;

    @NotBlank
    private String cron = "0 0 3 15 * *";

    @NotBlank
    private String zone = "America/Chicago";
  }

  /** OpenStreetMap source settings and named coverage. */
  @Data
  public static class Osm {
    @NotNull
    private URI endpoint = URI.create("https://overpass-api.de/api/interpreter");

    @NotNull
    @DurationMin(seconds = 1)
    private Duration timeout = Duration.ofSeconds(60);

    @Min(1)
    @Max(50_000)
    private int resultLimit = 20_000;

    private boolean includeFastFood = true;

    @Valid
    @NotEmpty
    private List<@NotNull @Valid Metro> metros = defaultMetros();

    @AssertTrue(message = "endpoint must be an HTTP(S) URL without user information")
    public boolean isEndpointSafe() {
      if (endpoint == null) {
        return true;
      }
      var scheme = endpoint.getScheme();
      return scheme != null
          && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          && endpoint.getHost() != null
          && !endpoint.getHost().isBlank()
          && endpoint.getUserInfo() == null;
    }

    @AssertTrue(message = "metro names and city/state ownership must be unique")
    public boolean isMetroCoverageUnique() {
      if (metros == null) {
        return true;
      }
      var metroNames = new HashSet<String>();
      var cityOwners = new HashSet<String>();
      for (var metro : metros) {
        if (metro == null) {
          continue;
        }
        if (metro.getName() != null
            && !metroNames.add(normalize(metro.getName()))) {
          return false;
        }
        if (metro.getCities() == null) {
          continue;
        }
        for (var city : metro.getCities()) {
          if (city != null
              && !cityOwners.add(normalize(metro.getState()) + ":" + normalize(city))) {
            return false;
          }
        }
      }
      return true;
    }

    private String normalize(String value) {
      return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static List<Metro> defaultMetros() {
      return new ArrayList<>(List.of(
          metro("Austin", "TX",
              List.of("Austin", "Round Rock", "Cedar Park", "Georgetown", "Pflugerville",
                  "Leander", "Hutto", "Manor", "Buda", "Kyle", "Bee Cave", "Lakeway",
                  "Dripping Springs", "Bastrop", "San Marcos"),
              29.95, -98.25, 30.75, -97.15),
          metro("San Francisco Bay Area", "CA",
              List.of("San Francisco", "Oakland", "Berkeley", "San Jose", "San Mateo",
                  "Palo Alto", "Mountain View", "Sunnyvale", "Santa Clara", "Fremont",
                  "Hayward", "Richmond", "Walnut Creek", "Redwood City", "Daly City"),
              37.20, -122.65, 38.20, -121.65),
          metro("New Orleans", "LA",
              List.of("New Orleans", "Metairie", "Kenner", "Gretna", "Harvey", "Westwego",
                  "Chalmette", "Slidell"),
              29.70, -90.45, 30.25, -89.65),
          metro("Dallas", "TX",
              List.of("Dallas", "Irving", "Garland", "Richardson", "Plano", "Mesquite",
                  "Carrollton", "Grand Prairie", "Addison"),
              32.45, -97.35, 33.15, -96.35)));
    }

    private static Metro metro(
        String name,
        String state,
        List<String> cities,
        double south,
        double west,
        double north,
        double east
    ) {
      var bounds = new BoundingBox();
      bounds.setSouth(south);
      bounds.setWest(west);
      bounds.setNorth(north);
      bounds.setEast(east);
      var metro = new Metro();
      metro.setName(name);
      metro.setState(state);
      metro.setCities(new ArrayList<>(cities));
      metro.setBounds(bounds);
      return metro;
    }
  }

  /** One named metro and its supported city/state coverage. */
  @Data
  public static class Metro {
    @NotBlank
    private String name;

    @NotBlank
    private String state;

    @NotEmpty
    private List<@NotBlank String> cities = new ArrayList<>();

    @Valid
    @NotNull
    private BoundingBox bounds = new BoundingBox();
  }

  /** Valid south/west/north/east geographic query bounds. */
  @Data
  public static class BoundingBox {
    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private double south;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private double west;

    @DecimalMin("-90.0")
    @DecimalMax("90.0")
    private double north;

    @DecimalMin("-180.0")
    @DecimalMax("180.0")
    private double east;

    @AssertTrue(message = "bounding box coordinates must be ordered south/north and west/east")
    public boolean isOrdered() {
      return south < north && west < east;
    }

    /** Formats the order required by the Overpass bounding-box syntax. */
    public String toOverpassValue() {
      return "%s,%s,%s,%s".formatted(south, west, north, east);
    }
  }
}

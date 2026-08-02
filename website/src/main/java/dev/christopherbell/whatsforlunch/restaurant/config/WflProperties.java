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
    // U.S. Census TIGERweb incorporated places and CDPs intersecting the configured
    // rectangles, January 1, 2025 vintage. Keep these lists aligned with application.yml.
    private static final List<String> AUSTIN_CENSUS_PLACES = List.of(
        "Austin", "Barton Creek", "Bastrop", "Bear Creek",
        "Bee Cave", "Belterra", "Bertram", "Briarcliff",
        "Brushy Creek", "Buda", "Burnet", "Camp Swift",
        "Canyon Lake", "Cedar Creek", "Cedar Park", "Circle D-KC Estates",
        "Coupland", "Creedmoor", "Double Horn", "Driftwood",
        "Dripping Springs", "Elgin", "Garfield", "Georgetown",
        "Granger", "Hays", "Hornsby Bend", "Hudson Bend",
        "Hutto", "Jonestown", "Kyle", "Lago Vista",
        "Lakeway", "Leander", "Liberty Hill", "Lost Creek",
        "Manchaca", "Manor", "Marble Falls", "McDade",
        "Mountain City", "Mustang Ridge", "Niederwald", "Pflugerville",
        "Point Venture", "Red Rock", "Rollingwood", "Rosanky",
        "Round Rock", "San Leanna", "San Marcos", "Santa Rita Ranch",
        "Serenada", "Shady Hollow", "Smithville", "Steiner Ranch",
        "Sunset Valley", "Taylor", "The Hills", "Thorndale",
        "Thrall", "Uhland", "Volente", "Webberville",
        "Weir", "Wells Branch", "West Lake Hills", "Wimberley",
        "Woodcreek", "Wyldwood");

    private static final List<String> BAY_AREA_CENSUS_PLACES = List.of(
        "Acalanes Ridge", "Alameda", "Alamo", "Albany",
        "Alhambra Valley", "Alto", "Alum Rock", "American Canyon",
        "Antioch", "Ashland", "Atherton", "Bay Point",
        "Bayview", "Baywood Park", "Belmont", "Belvedere",
        "Benicia", "Berkeley", "Bethel Island", "Black Point-Green Point",
        "Blackhawk", "Brentwood", "Brisbane", "Broadmoor",
        "Burbank", "Burlingame", "Byron", "Cambrian Park",
        "Camino Tassajara", "Campbell", "Castle Hill", "Castro Valley",
        "Cherryland", "Clayton", "Clyde", "Colma",
        "Concord", "Contra Costa Centre", "Corte Madera", "Crockett",
        "Cupertino", "Daly City", "Danville", "Diablo",
        "Dublin", "East Foothills", "East Palo Alto", "East Richmond Heights",
        "El Cerrito", "El Granada", "El Sobrante", "Emerald Lake Hills",
        "Emeryville", "Fairfax", "Fairfield", "Fairview",
        "Foster City", "Fremont", "Fruitdale", "Half Moon Bay",
        "Hayward", "Hercules", "Highlands", "Hillsborough",
        "Kensington", "Kentfield", "Knightsen", "La Honda",
        "Ladera", "Lafayette", "Larkspur", "Livermore",
        "Loma Mar", "Los Altos", "Los Altos Hills", "Los Gatos",
        "Loyola", "Lucas Valley-Marinwood", "Marin City", "Martinez",
        "Menlo Park", "Mill Valley", "Millbrae", "Milpitas",
        "Montalvin Manor", "Montara", "Monte Sereno", "Moraga",
        "Moss Beach", "Mountain View", "Muir Beach", "Newark",
        "Norris Canyon", "North Fair Oaks", "North Gate", "North Richmond",
        "Novato", "Oakland", "Oakley", "Orinda",
        "Pacheco", "Pacifica", "Palo Alto", "Pescadero",
        "Piedmont", "Pinole", "Pittsburg", "Pleasant Hill",
        "Pleasanton", "Port Costa", "Portola Valley", "Redwood City",
        "Reliez Valley", "Richmond", "Rio Vista", "Rodeo",
        "Rollingwood", "Ross", "San Anselmo", "San Bruno",
        "San Carlos", "San Francisco", "San Geronimo", "San Jose",
        "San Leandro", "San Lorenzo", "San Mateo", "San Miguel",
        "San Pablo", "San Rafael", "San Ramon", "Santa Clara",
        "Santa Venetia", "Saranap", "Saratoga", "Sausalito",
        "Shell Ridge", "Sleepy Hollow", "South San Francisco", "Stanford",
        "Stinson Beach", "Strawberry", "Sunnyvale", "Sunol",
        "Tamalpais-Homestead Valley", "Tara Hills", "Tiburon", "Union City",
        "Vallejo", "Vine Hill", "Walnut Creek", "West Menlo Park",
        "Woodacre", "Woodside");

    private static final List<String> NEW_ORLEANS_CENSUS_PLACES = List.of(
        "Ama", "Arabi", "Avondale", "Barataria",
        "Bayou Gauche", "Belle Chasse", "Boutte", "Bridge City",
        "Chalmette", "Delacroix", "Des Allemands", "Destrehan",
        "Eden Isle", "Elmwood", "Estelle", "Gretna",
        "Hahnville", "Harahan", "Harvey", "Jean Lafitte",
        "Jefferson", "Kenner", "Lacombe", "Lafitte",
        "Laplace", "Luling", "Marrero", "Meraux",
        "Metairie", "Montz", "New Orleans", "New Orleans Station",
        "New Sarpy", "Norco", "Paradis", "Poydras",
        "River Ridge", "Slidell", "St. Rose", "Taft",
        "Terrytown", "Timberlane", "Violet", "Waggaman",
        "Westwego", "Woodmere");

    private static final List<String> DALLAS_CENSUS_PLACES = List.of(
        "Addison", "Allen", "Argyle", "Arlington",
        "Balch Springs", "Bartonville", "Bear Creek Ranch", "Bedford",
        "Blue Mound", "Briaroaks", "Bristol", "Burleson",
        "Carrollton", "Cedar Hill", "Cockrell Hill", "Colleyville",
        "Combine", "Coppell", "Copper Canyon", "Corinth",
        "Corral City", "Cottonwood", "Crandall", "Cross Timber",
        "Crowley", "Dallas", "Dalworthington Gardens", "Denton",
        "DeSoto", "DISH", "Double Oak", "Duncanville",
        "Edgecliff Village", "Euless", "Everman", "Fairview",
        "Farmers Branch", "Farmersville", "Fate", "Ferris",
        "Flower Mound", "Forest Hill", "Forney", "Fort Worth",
        "Frisco", "Garland", "Glenn Heights", "Grand Prairie",
        "Grapevine", "Grays Prairie", "Hackberry", "Haltom City",
        "Haslet", "Heartland", "Heath", "Hebron",
        "Hickory Creek", "Highland Park", "Highland Village", "Hurst",
        "Hutchins", "Irving", "Josephine", "Justin",
        "Kaufman", "Keller", "Kennedale", "Lake Dallas",
        "Lakewood Village", "Lancaster", "Lantana", "Lavon",
        "Lewisville", "Little Elm", "Lucas", "Mansfield",
        "McKinney", "McLendon-Chisholm", "Mesquite", "Midlothian",
        "Mobile City", "Murphy", "Nevada", "North Richland Hills",
        "Northlake", "Oak Leaf", "Ovilla", "Pantego",
        "Parker", "Pecan Hill", "Plano", "Princeton",
        "Red Oak", "Rendon", "Richardson", "Richland Hills",
        "Roanoke", "Rockwall", "Rosser", "Rowlett",
        "Royse City", "Sachse", "Saginaw", "Scurry",
        "Seagoville", "Seis Lagos", "Shady Shores", "Southlake",
        "St. Paul", "Sunnyvale", "Talty", "Terrell",
        "The Colony", "The Homesteads", "Travis Ranch", "Trophy Club",
        "University Park", "Venus", "Watauga", "Waxahachie",
        "Westlake", "Wilmer", "Wylie");

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
              AUSTIN_CENSUS_PLACES,
              29.95, -98.25, 30.75, -97.15),
          metro("San Francisco Bay Area", "CA",
              BAY_AREA_CENSUS_PLACES,
              37.20, -122.65, 38.20, -121.65),
          metro("New Orleans", "LA",
              NEW_ORLEANS_CENSUS_PLACES,
              29.70, -90.45, 30.25, -89.65),
          metro("Dallas", "TX",
              DALLAS_CENSUS_PLACES,
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

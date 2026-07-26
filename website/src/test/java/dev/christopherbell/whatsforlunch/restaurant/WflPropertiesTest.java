package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import jakarta.validation.Validation;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class WflPropertiesTest {
  @Test
  void validConfigurationHasNoViolations() {
    assertThat(violations(validProperties())).isEmpty();
  }

  @Test
  void rejectsDuplicateMetroAndCityOwnership() {
    var properties = validProperties();
    properties.getRestaurantImport().getOsm().setMetros(List.of(
        metro("Austin", "TX", List.of("Austin"), 29, -99, 31, -97),
        metro("austin", "TX", List.of("AUSTIN"), 32, -98, 33, -96)));

    assertThat(violations(properties))
        .anyMatch(path -> path.endsWith("metroCoverageUnique"));
  }

  @Test
  void rejectsReversedBoundingBoxAndUnsafeEndpoint() {
    var properties = validProperties();
    properties.getRestaurantImport().getOsm().setEndpoint(URI.create("file:///tmp/data"));
    properties.getRestaurantImport().getOsm().setMetros(List.of(
        metro("Austin", "TX", List.of("Austin"), 31, -97, 29, -99)));

    assertThat(violations(properties))
        .anyMatch(path -> path.endsWith("endpointSafe"))
        .anyMatch(path -> path.contains("ordered"));
  }

  @Test
  void rejectsLeaseThatCannotCoverTheRemoteRequest() {
    var properties = validProperties();
    properties.getRestaurantImport().setLeaseDuration(Duration.ofSeconds(30));
    properties.getRestaurantImport().getOsm().setTimeout(Duration.ofSeconds(60));

    assertThat(violations(properties))
        .anyMatch(path -> path.endsWith("leaseCoversRemoteRequest"));
  }

  private List<String> violations(WflProperties properties) {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      return factory.getValidator().validate(properties).stream()
          .map(violation -> violation.getPropertyPath().toString())
          .toList();
    }
  }

  private WflProperties validProperties() {
    var properties = new WflProperties();
    properties.getRestaurantImport().getOsm().setEndpoint(URI.create("https://overpass.example/api"));
    properties.getRestaurantImport().getOsm().setTimeout(Duration.ofSeconds(60));
    properties.getRestaurantImport().setLeaseDuration(Duration.ofMinutes(2));
    properties.getRestaurantImport().getOsm().setMetros(List.of(
        metro("Austin", "TX", List.of("Austin", "Round Rock"), 29, -99, 31, -97)));
    return properties;
  }

  private WflProperties.Metro metro(
      String name,
      String state,
      List<String> cities,
      double south,
      double west,
      double north,
      double east
  ) {
    var bounds = new WflProperties.BoundingBox();
    bounds.setSouth(south);
    bounds.setWest(west);
    bounds.setNorth(north);
    bounds.setEast(east);
    var metro = new WflProperties.Metro();
    metro.setName(name);
    metro.setState(state);
    metro.setCities(cities);
    metro.setBounds(bounds);
    return metro;
  }
}

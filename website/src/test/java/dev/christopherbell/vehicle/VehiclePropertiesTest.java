package dev.christopherbell.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.vehicle.model.VehicleProperties;
import jakarta.validation.Validation;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class VehiclePropertiesTest {
  @Test
  void rejectsEnabledRandomVinBelowMinimumDelayOrLeaseBelowRequestBudget() {
    var properties = new VehicleProperties();
    var randomVin = properties.getRandomVin();
    randomVin.setEnabled(true);
    randomVin.setFixedDelay(Duration.ofSeconds(30));
    randomVin.setMinimumSafeDelay(Duration.ofMinutes(1));
    randomVin.setRequestTimeout(Duration.ofSeconds(15));
    randomVin.setLeaseDuration(Duration.ofSeconds(15));

    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      var paths = validatorFactory.getValidator().validate(properties).stream()
          .map(violation -> violation.getPropertyPath().toString())
          .toList();

      assertThat(paths).contains(
          "randomVin.scheduleSafeWhenEnabled",
          "randomVin.leaseLongerThanRequest");
    }
  }

  @Test
  void defaultsAreDisabledAndProductionSafe() {
    var randomVin = new VehicleProperties().getRandomVin();

    assertThat(randomVin.isEnabled()).isFalse();
    assertThat(randomVin.getInitialDelay()).isEqualTo(Duration.ofMinutes(1));
    assertThat(randomVin.getFixedDelay()).isEqualTo(Duration.ofMinutes(10));
    assertThat(randomVin.getMinimumSafeDelay()).isEqualTo(Duration.ofMinutes(1));
    assertThat(randomVin.getMaxCallsPerDay()).isEqualTo(144);
  }

  @Test
  void rejectsEqualVehicleImportStateIds() {
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setStateId("shared-state");
    properties.getRandomVin().setStateId("shared-state");

    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      var paths = validatorFactory.getValidator().validate(properties).stream()
          .map(violation -> violation.getPropertyPath().toString())
          .toList();

      assertThat(paths).contains("importStateIdsDistinct");
    }
  }

  @Test
  void equalVehicleImportStateIdsFailConfigurationStartup() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "vehicles.nhtsa-vin.url=https://example.invalid/nhtsa",
            "vehicles.nhtsa-vin.connect-timeout=1s",
            "vehicles.nhtsa-vin.request-timeout=1s",
            "vehicles.nhtsa-vin.cooldown=1s",
            "vehicles.nhtsa-vin.state-id=shared-state",
            "vehicles.nhtsa-vin.state-note=NHTSA",
            "vehicles.random-vin.url=https://example.invalid/randomvin",
            "vehicles.random-vin.robots-url=https://example.invalid/robots.txt",
            "vehicles.random-vin.path=/",
            "vehicles.random-vin.user-agent=test",
            "vehicles.random-vin.connect-timeout=1s",
            "vehicles.random-vin.request-timeout=1s",
            "vehicles.random-vin.cooldown=1s",
            "vehicles.random-vin.state-id=shared-state",
            "vehicles.random-vin.state-note=RandomVIN",
            "vehicles.random-vin.legacy-import-note=RandomVIN legacy",
            "vehicles.vin-decoder.rate-limit-window=1s")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .hasMessageContaining("importStateIdsDistinct");
        });
  }

  @Test
  void shippedVehicleImportStateIdsRemainProviderSpecific() throws IOException {
    var environment = new StandardEnvironment();
    var loader = new YamlPropertySourceLoader();
    for (var propertySource : loader.load(
        "application.yml", new ClassPathResource("application.yml"))) {
      environment.getPropertySources().addLast(propertySource);
    }

    var properties = Binder.get(environment)
        .bind("vehicles", VehicleProperties.class)
        .orElseThrow(() -> new AssertionError("vehicles configuration was not bound"));

    assertThat(properties.getNhtsaVin().getStateId()).isEqualTo("nhtsa");
    assertThat(properties.getRandomVin().getStateId()).isEqualTo("randomvin");
    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      assertThat(validatorFactory.getValidator().validate(properties)).isEmpty();
    }
  }

  @Test
  void vinDecoderDefaultsToTenThousandBuckets() {
    assertThat(new VehicleProperties().getVinDecoder().getMaximumBuckets())
        .isEqualTo(10_000);
  }

  @ParameterizedTest
  @ValueSource(ints = {99, 100_001})
  void rejectsVinDecoderMaximumBucketsOutsideOperationalBounds(int maximumBuckets) {
    var vinDecoder = new VehicleProperties.VinDecoder();
    vinDecoder.setMaximumBuckets(maximumBuckets);

    try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
      var paths = validatorFactory.getValidator().validate(vinDecoder).stream()
          .map(violation -> violation.getPropertyPath().toString())
          .toList();

      assertThat(paths).contains("maximumBuckets");
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(VehicleProperties.class)
  static class PropertiesConfiguration {}
}

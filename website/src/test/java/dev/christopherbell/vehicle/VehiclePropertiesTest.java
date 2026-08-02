package dev.christopherbell.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.vehicle.model.VehicleProperties;
import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}

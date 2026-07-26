package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitPropertiesTest {
  @Test
  void rejectsNonPositiveBucketCountAtTheConfigurationBoundary() {
    var properties = new RateLimitProperties();
    properties.setMaxBuckets(0);

    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(properties))
          .anyMatch(violation -> "maxBuckets".equals(violation.getPropertyPath().toString()));
    }
  }

  @Test
  void rejectsNonPositiveRuleWindowAtTheConfigurationBoundary() {
    var properties = new RateLimitProperties();
    properties.getRules().getFirst().setWindow(Duration.ZERO);

    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(properties))
          .anyMatch(violation -> violation.getPropertyPath().toString().endsWith("window"));
    }
  }
}

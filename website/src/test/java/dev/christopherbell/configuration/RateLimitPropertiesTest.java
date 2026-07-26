package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.Duration;
import java.util.List;
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

  @Test
  void rejectsDuplicateRuleNamesAtTheConfigurationBoundary() {
    var properties = new RateLimitProperties();
    properties.setRules(List.of(
        new RateLimitProperties.Rule(
            "duplicate", 1, Duration.ofSeconds(1), List.of("GET"), List.of("/one")),
        new RateLimitProperties.Rule(
            "duplicate", 2, Duration.ofSeconds(2), List.of("POST"), List.of("/two"))));

    try (var factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(properties))
          .anyMatch(violation -> "ruleNamesUnique"
              .equals(violation.getPropertyPath().toString()));
    }
  }
}

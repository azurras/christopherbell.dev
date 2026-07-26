package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class MigrationPropertiesTest {
  @Test
  void bindsBoundedLeaseDuration() {
    var environment = new MockEnvironment()
        .withProperty("app.migrations.lease-duration", "2m");

    var properties = Binder.get(environment)
        .bind("app.migrations", Bindable.of(MigrationProperties.class))
        .orElseThrow(() -> new AssertionError("Migration properties did not bind."));

    assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void rejectsLeaseDurationBelowSafetyBoundary() {
    assertThatThrownBy(() -> new MigrationProperties(Duration.ofSeconds(29)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 30 seconds");
  }
}

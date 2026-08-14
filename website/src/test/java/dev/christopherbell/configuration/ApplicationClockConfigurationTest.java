package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ApplicationClockConfigurationTest {
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(ApplicationClockConfiguration.class);

  @Test
  void suppliesOneUtcClockIndependentlyOfThePersistenceBackend() {
    contextRunner.run(context -> {
      assertThat(context.getStartupFailure()).isNull();
      assertThat(context).hasSingleBean(Clock.class);
      assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
    });
  }
}

package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.time.Instant;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class LibreHardwareCpuTemperatureProviderTest {
  private static final Instant NOW = Instant.parse("2026-07-12T12:00:00Z");

  @Test
  void publishesBoundedCpuTemperatureFromLibreHardwareClient() {
    var provider = new LibreHardwareCpuTemperatureProvider(() -> OptionalDouble.of(63.5));

    var reading = provider.read(NOW).get("cpu.temperature");

    assertThat(reading.value()).isEqualTo(63.5);
    assertThat(reading.status()).isEqualTo(MetricStatus.AVAILABLE);
  }

  @Test
  void degradesToUnavailableWhenSensorHasNoValidValueOrClientFails() {
    var empty = new LibreHardwareCpuTemperatureProvider(OptionalDouble::empty);
    var failed = new LibreHardwareCpuTemperatureProvider(() -> {
      throw new IllegalStateException("native sensor unavailable");
    });

    assertThat(empty.read(NOW).get("cpu.temperature").status())
        .isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(failed.read(NOW).get("cpu.temperature").status())
        .isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(failed.read(NOW).get("cpu.temperature").detail())
        .doesNotContain("native", "sensor");
  }
}

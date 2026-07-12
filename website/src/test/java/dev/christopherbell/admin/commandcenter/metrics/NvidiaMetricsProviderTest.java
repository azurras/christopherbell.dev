package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NvidiaMetricsProviderTest {
  private static final Instant SAMPLED_AT = Instant.parse("2026-07-12T12:00:00Z");

  @Test
  void parsesNvidiaCsvAndPublishesFiveMetrics() {
    var sample = NvidiaMetricsProvider.parse("3, 37, 2076, 12282, 18.4\n");
    var provider = new NvidiaMetricsProvider(
        "nvidia-smi", Duration.ofSeconds(2),
        (command, timeout) -> new NvidiaMetricsProvider.CommandResult(0, "3, 37, 2076, 12282, 18.4\n", false));

    var readings = provider.read(SAMPLED_AT);

    assertThat(sample.utilizationPercent()).isEqualTo(3.0);
    assertThat(sample.temperatureCelsius()).isEqualTo(37.0);
    assertThat(sample.memoryUsedMegabytes()).isEqualTo(2076.0);
    assertThat(sample.memoryTotalMegabytes()).isEqualTo(12282.0);
    assertThat(sample.powerDrawWatts()).isEqualTo(18.4);
    assertThat(readings).hasSize(5);
    assertThat(readings.get("gpu.temperature").status()).isEqualTo(MetricStatus.AVAILABLE);
  }

  @Test
  void invokesOnlyTheFixedNvidiaQuery() {
    var commands = new java.util.ArrayList<List<String>>();
    var provider = new NvidiaMetricsProvider(
        "nvidia-smi", Duration.ofMillis(250),
        (command, timeout) -> {
          commands.add(command);
          assertThat(timeout).isEqualTo(Duration.ofMillis(250));
          return new NvidiaMetricsProvider.CommandResult(0, "3, 37, 2076, 12282, 18.4", false);
        });

    provider.read(SAMPLED_AT);

    assertThat(commands).containsExactly(List.of(
        "nvidia-smi",
        "--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total,power.draw",
        "--format=csv,noheader,nounits"));
  }

  @Test
  void rejectsMalformedCsv() {
    assertThatThrownBy(() -> NvidiaMetricsProvider.parse("3, 37, 2076, 12282, 18.4, extra"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metric count");
    assertThatThrownBy(() -> NvidiaMetricsProvider.parse("driver error"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void marksOnlyNvidiaTemperatureUnavailableForZeroNaNOrNotAvailable() {
    for (var temperature : List.of("0", "NaN", "N/A")) {
      var provider = new NvidiaMetricsProvider(
          "nvidia-smi", Duration.ofSeconds(2),
          (command, timeout) -> new NvidiaMetricsProvider.CommandResult(
              0, "3, " + temperature + ", 2076, 12282, 18.4", false));

      var readings = provider.read(SAMPLED_AT);

      assertThat(readings.get("gpu.temperature").value()).isNull();
      assertThat(readings.get("gpu.temperature").status()).isEqualTo(MetricStatus.UNAVAILABLE);
      assertThat(readings.get("gpu.usage").value()).isEqualTo(3.0);
      assertThat(readings.get("gpu.memory.used").value()).isEqualTo(2076.0);
      assertThat(readings.get("gpu.memory.total").value()).isEqualTo(12282.0);
      assertThat(readings.get("gpu.power.draw").value()).isEqualTo(18.4);
    }
  }

  @Test
  void reportsTimeoutNonZeroExitAndMissingExecutableAsProviderFailures() {
    var timeout = new NvidiaMetricsProvider(
        "nvidia-smi", Duration.ofMillis(1),
        (command, duration) -> new NvidiaMetricsProvider.CommandResult(-1, "", true));
    var nonZero = new NvidiaMetricsProvider(
        "nvidia-smi", Duration.ofSeconds(1),
        (command, duration) -> new NvidiaMetricsProvider.CommandResult(7, "driver error", false));
    var missing = new NvidiaMetricsProvider(
        "missing-nvidia-smi", Duration.ofSeconds(1),
        (command, duration) -> { throw new IOException("not found"); });

    assertThatThrownBy(() -> timeout.read(SAMPLED_AT)).hasMessageContaining("timed out");
    assertThatThrownBy(() -> nonZero.read(SAMPLED_AT)).hasMessageContaining("exit code 7");
    assertThatThrownBy(() -> missing.read(SAMPLED_AT)).hasMessageContaining("unavailable");
  }
}

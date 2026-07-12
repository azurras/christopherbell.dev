package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class ApplicationHostMetricsProviderTest {
  private static final Instant START = Instant.parse("2026-07-12T12:00:00Z");

  @Test
  void publishesServicePortStartAndLocalResponseMetrics() {
    var properties = new CommandCenterProperties();
    properties.setProductionPort(8080);
    properties.setCommitIdentifier("abc123def");
    var provider = new ApplicationHostMetricsProvider(
        properties, Clock.fixed(START, ZoneOffset.UTC),
        () -> new ApplicationHostMetricsProvider.ProbeResult(Optional.of(true), OptionalDouble.of(12.5)));

    var readings = provider.read(START.plusSeconds(5));

    assertThat(readings.get("production.service.running").value()).isEqualTo(1.0);
    assertThat(readings.get("production.port").value()).isEqualTo(8080.0);
    assertThat(readings.get("application.last-start").value()).isEqualTo((double) START.getEpochSecond());
    assertThat(readings.get("application.local-response").value()).isEqualTo(12.5);
    assertThat(readings.get("application.commit").detail()).isEqualTo("abc123def");
    assertThat(readings.get("application.commit").unit()).isEqualTo("commit");
    assertThat(readings.get("application.commit").status()).isEqualTo(MetricStatus.AVAILABLE);
  }

  @Test
  void probeFailuresHaveExplicitUnavailableSemantics() {
    var provider = new ApplicationHostMetricsProvider(
        new CommandCenterProperties(), Clock.fixed(START, ZoneOffset.UTC),
        () -> new ApplicationHostMetricsProvider.ProbeResult(Optional.empty(), OptionalDouble.empty()));

    var readings = provider.read(START);

    assertThat(readings.get("production.service.running").status()).isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(readings.get("application.local-response").status()).isEqualTo(MetricStatus.UNAVAILABLE);
  }
}

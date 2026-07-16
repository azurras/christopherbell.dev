package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationHostMetricsProviderTest {
  private static final Instant START = Instant.parse("2026-07-12T12:00:00Z");
  @TempDir Path tempDir;

  @Test
  void publishesServicePortStartAndLocalResponseMetrics() {
    var properties = new CommandCenterProperties();
    properties.setProductionPort(8080);
    properties.setCommitIdentifier("abc123def");
    var provider = new ApplicationHostMetricsProvider(
        properties, Clock.fixed(START, ZoneOffset.UTC),
        () -> new ApplicationHostMetricsProvider.ProbeResult(
            Optional.of(true), OptionalDouble.of(12.5), Optional.empty()));

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
  void usesTheActiveReleaseMetadataWhenNoConfiguredCommitExists() {
    var provider = new ApplicationHostMetricsProvider(
        new CommandCenterProperties(), Clock.fixed(START, ZoneOffset.UTC),
        () -> new ApplicationHostMetricsProvider.ProbeResult(
            Optional.of(true),
            OptionalDouble.of(12.5),
            Optional.of("0123456789abcdef0123456789abcdef01234567")));

    var commit = provider.read(START).get("application.commit");

    assertThat(commit.status()).isEqualTo(MetricStatus.AVAILABLE);
    assertThat(commit.detail()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
  }

  @Test
  void readsOnlyAValidBoundedReleaseMetadataSha() throws Exception {
    Path metadata = tempDir.resolve("release.json");
    Files.writeString(metadata,
        "{\"sha\":\"0123456789abcdef0123456789abcdef01234567\"}");

    assertThat(ApplicationHostMetricsProvider.readReleaseCommit(metadata))
        .contains("0123456789abcdef0123456789abcdef01234567");

    Files.writeString(metadata, "{\"sha\":\"NOT-A-RELEASE\"}");
    assertThat(ApplicationHostMetricsProvider.readReleaseCommit(metadata)).isEmpty();
  }

  @Test
  void probeFailuresHaveExplicitUnavailableSemantics() {
    var provider = new ApplicationHostMetricsProvider(
        new CommandCenterProperties(), Clock.fixed(START, ZoneOffset.UTC),
        () -> new ApplicationHostMetricsProvider.ProbeResult(
            Optional.empty(), OptionalDouble.empty(), Optional.empty()));

    var readings = provider.read(START);

    assertThat(readings.get("production.service.running").status()).isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(readings.get("application.local-response").status()).isEqualTo(MetricStatus.UNAVAILABLE);
  }
}

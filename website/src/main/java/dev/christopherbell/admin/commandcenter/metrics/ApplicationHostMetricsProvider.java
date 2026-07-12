package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricReading;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Publishes fixed production-service and local application reachability metrics. */
@Component
public final class ApplicationHostMetricsProvider implements HostMetricsProvider {
  private final CommandCenterProperties properties;
  private final Instant applicationStartedAt;
  private final OperationalProbe probe;

  public ApplicationHostMetricsProvider(CommandCenterProperties properties, Clock clock) {
    this(properties, clock, new DefaultOperationalProbe(properties));
  }

  ApplicationHostMetricsProvider(
      CommandCenterProperties properties, Clock clock, OperationalProbe probe) {
    this.properties = properties;
    this.applicationStartedAt = clock.instant();
    this.probe = probe;
  }

  @Override
  public Map<String, MetricReading> read(Instant sampledAt) {
    ProbeResult result;
    try {
      result = probe.read();
    } catch (RuntimeException failure) {
      result = new ProbeResult(Optional.empty(), OptionalDouble.empty());
    }
    var readings = new LinkedHashMap<String, MetricReading>();
    readings.put("production.port", available(
        "production.port", "Production port", properties.getProductionPort(), "port", sampledAt));
    readings.put("application.last-start", available(
        "application.last-start", "Last application start", applicationStartedAt.getEpochSecond(),
        "epoch-seconds", sampledAt));
    String commit = safeCommit(properties.getCommitIdentifier());
    readings.put("application.commit", commit == null
        ? unavailable("application.commit", "Application commit", "state", sampledAt)
        : available("application.commit", "Application commit " + commit, 1, "state", sampledAt));
    readings.put("production.service.running", result.serviceRunning().isPresent()
        ? available("production.service.running", "Production service",
            result.serviceRunning().orElseThrow() ? 1 : 0, "state", sampledAt)
        : unavailable("production.service.running", "Production service", "state", sampledAt));
    readings.put("application.local-response", result.responseMillis().isPresent()
        ? available("application.local-response", "Local response time",
            result.responseMillis().getAsDouble(), "milliseconds", sampledAt)
        : unavailable("application.local-response", "Local response time", "milliseconds", sampledAt));
    return Map.copyOf(readings);
  }

  private static String safeCommit(String commit) {
    if (commit == null || !commit.matches("[A-Za-z0-9._-]{1,64}") || "unknown".equalsIgnoreCase(commit)) {
      return null;
    }
    return commit;
  }

  private static MetricReading available(
      String key, String label, double value, String unit, Instant sampledAt) {
    return new MetricReading(key, label, value, unit, MetricStatus.AVAILABLE, sampledAt, null);
  }

  private static MetricReading unavailable(
      String key, String label, String unit, Instant sampledAt) {
    return new MetricReading(key, label, null, unit, MetricStatus.UNAVAILABLE, sampledAt,
        "Operational probe unavailable");
  }

  interface OperationalProbe {
    ProbeResult read();
  }

  record ProbeResult(Optional<Boolean> serviceRunning, OptionalDouble responseMillis) {}

  private static final class DefaultOperationalProbe implements OperationalProbe {
    private final CommandCenterProperties properties;
    private final HttpClient client = HttpClient.newBuilder().build();

    private DefaultOperationalProbe(CommandCenterProperties properties) {
      this.properties = properties;
    }

    @Override
    public ProbeResult read() {
      return new ProbeResult(serviceRunning(), responseMillis());
    }

    private Optional<Boolean> serviceRunning() {
      if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
        return Optional.empty();
      }
      Process process = null;
      try {
        String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        process = new ProcessBuilder(
            Path.of(windows, "System32", "sc.exe").toString(),
            "query", properties.getProductionServiceName())
            .redirectErrorStream(true)
            .start();
        if (!process.waitFor(properties.getProviderTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          return Optional.empty();
        }
        String output = new String(process.getInputStream().readNBytes(8_192), StandardCharsets.UTF_8);
        return Optional.of(process.exitValue() == 0 && output.contains("RUNNING"));
      } catch (Exception failure) {
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return Optional.empty();
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
        }
      }
    }

    private OptionalDouble responseMillis() {
      try {
        var request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + properties.getProductionPort() + "/"))
            .timeout(properties.getProviderTimeout())
            .GET()
            .build();
        long started = System.nanoTime();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 100) {
          return OptionalDouble.empty();
        }
        return OptionalDouble.of(Duration.ofNanos(System.nanoTime() - started).toNanos() / 1_000_000.0);
      } catch (Exception failure) {
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return OptionalDouble.empty();
      }
    }
  }
}

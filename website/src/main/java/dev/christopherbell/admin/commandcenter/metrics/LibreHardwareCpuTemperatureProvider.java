package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricReading;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Publishes CPU temperature from the bounded LibreHardwareMonitor sensor client. */
@Component
public final class LibreHardwareCpuTemperatureProvider implements HostMetricsProvider {
  private final CpuTemperatureSensorClient client;

  public LibreHardwareCpuTemperatureProvider(LibreHardwareCpuTemperatureClient client) {
    this.client = client;
  }

  LibreHardwareCpuTemperatureProvider(CpuTemperatureSensorClient client) {
    this.client = client;
  }

  @Override
  public Map<String, MetricReading> read(Instant sampledAt) {
    try {
      var value = client.readCelsius();
      return Map.of("cpu.temperature", value.isPresent()
          ? new MetricReading("cpu.temperature", "CPU temperature", value.getAsDouble(),
              "celsius", MetricStatus.AVAILABLE, sampledAt, null)
          : unavailable(sampledAt));
    } catch (RuntimeException failure) {
      return Map.of("cpu.temperature", unavailable(sampledAt));
    }
  }

  private static MetricReading unavailable(Instant sampledAt) {
    return new MetricReading("cpu.temperature", "CPU temperature", null, "celsius",
        MetricStatus.UNAVAILABLE, sampledAt, "CPU temperature unavailable");
  }
}

package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricReading;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Reads NVIDIA GPU data with a fixed, bounded {@code nvidia-smi} invocation. */
@Component
public class NvidiaMetricsProvider implements HostMetricsProvider {
  static final List<String> QUERY_ARGUMENTS = List.of(
      "--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total,power.draw",
      "--format=csv,noheader,nounits");

  private final String executable;
  private final Duration timeout;
  private final CommandRunner commandRunner;

  @Autowired
  public NvidiaMetricsProvider(CommandCenterProperties properties) {
    this("nvidia-smi", properties.getProviderTimeout(), NvidiaMetricsProvider::runCommand);
  }

  NvidiaMetricsProvider(String executable, Duration timeout, CommandRunner commandRunner) {
    this.executable = executable;
    this.timeout = timeout;
    this.commandRunner = commandRunner;
  }

  @Override
  public Map<String, MetricReading> read(Instant sampledAt) {
    var command = new java.util.ArrayList<String>();
    command.add(executable);
    command.addAll(QUERY_ARGUMENTS);
    final CommandResult result;
    try {
      result = commandRunner.run(List.copyOf(command), timeout);
    } catch (IOException failure) {
      throw new MetricProviderException("NVIDIA metrics executable is unavailable.", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new MetricProviderException("NVIDIA metrics collection was interrupted.", failure);
    }
    if (result.timedOut()) {
      throw new MetricProviderException("NVIDIA metrics collection timed out.");
    }
    if (result.exitCode() != 0) {
      throw new MetricProviderException("NVIDIA metrics process returned exit code " + result.exitCode() + ".");
    }

    var sample = parse(result.stdout());
    var readings = new LinkedHashMap<String, MetricReading>();
    readings.put("gpu.usage", reading(
        "gpu.usage", "GPU usage", sample.utilizationPercent(), "percent", sampledAt));
    readings.put("gpu.temperature", reading(
        "gpu.temperature", "GPU temperature", sample.temperatureCelsius(), "celsius", sampledAt));
    readings.put("gpu.memory.used", reading(
        "gpu.memory.used", "GPU memory used", sample.memoryUsedMegabytes(), "megabytes", sampledAt));
    readings.put("gpu.memory.total", reading(
        "gpu.memory.total", "GPU memory total", sample.memoryTotalMegabytes(), "megabytes", sampledAt));
    readings.put("gpu.power.draw", reading(
        "gpu.power.draw", "GPU power draw", sample.powerDrawWatts(), "watts", sampledAt));
    return Map.copyOf(readings);
  }

  static NvidiaSample parse(String csv) {
    var columns = csv.strip().split("\\s*,\\s*");
    if (columns.length != 5) {
      throw new IllegalArgumentException("Unexpected NVIDIA metric count.");
    }
    try {
      var sample = new NvidiaSample(
          optionalNumber(columns[0]),
          optionalTemperature(columns[1]),
          optionalNumber(columns[2]),
          optionalNumber(columns[3]),
          optionalNumber(columns[4]));
      return sample;
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("NVIDIA metrics contain a non-numeric value.", failure);
    }
  }

  private static Double optionalTemperature(String value) {
    var temperature = optionalNumber(value);
    return temperature == null || temperature <= 0 ? null : temperature;
  }

  private static Double optionalNumber(String value) {
    if (value.equalsIgnoreCase("N/A")) {
      return null;
    }
    var number = Double.parseDouble(value);
    return Double.isFinite(number) ? number : null;
  }

  private static CommandResult runCommand(List<String> command, Duration timeout)
      throws IOException, InterruptedException {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    return readProcess(process, timeout);
  }

  static CommandResult readProcess(Process process, Duration timeout)
      throws IOException, InterruptedException {
    final boolean finished;
    try {
      finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      process.destroyForcibly();
      throw failure;
    }
    if (!finished) {
      process.destroyForcibly();
      return new CommandResult(-1, "", true);
    }
    return new CommandResult(
        process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), false);
  }

  private static MetricReading reading(
      String key, String label, Double value, String unit, Instant sampledAt) {
    return value == null
        ? new MetricReading(
            key, label, null, unit, MetricStatus.UNAVAILABLE, sampledAt, "Metric unavailable")
        : new MetricReading(key, label, value, unit, MetricStatus.AVAILABLE, sampledAt, null);
  }

  record NvidiaSample(
      Double utilizationPercent,
      Double temperatureCelsius,
      Double memoryUsedMegabytes,
      Double memoryTotalMegabytes,
      Double powerDrawWatts) {}

  record CommandResult(int exitCode, String stdout, boolean timedOut) {}

  @FunctionalInterface
  interface CommandRunner {
    CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
  }

  /** Indicates that the optional NVIDIA provider could not produce a valid sample. */
  static final class MetricProviderException extends RuntimeException {
    private MetricProviderException(String message) {
      super(message);
    }

    private MetricProviderException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

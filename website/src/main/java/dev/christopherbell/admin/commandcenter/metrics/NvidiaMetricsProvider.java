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

/** Reads NVIDIA GPU data with a fixed, bounded {@code nvidia-smi} invocation. */
@Component
public class NvidiaMetricsProvider implements HostMetricsProvider {
  static final List<String> QUERY_ARGUMENTS = List.of(
      "--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total,power.draw",
      "--format=csv,noheader,nounits");

  private final String executable;
  private final Duration timeout;
  private final CommandRunner commandRunner;

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
    readings.put("gpu.usage", available("gpu.usage", "GPU usage", sample.utilizationPercent(), "percent", sampledAt));
    readings.put("gpu.temperature", available(
        "gpu.temperature", "GPU temperature", sample.temperatureCelsius(), "celsius", sampledAt));
    readings.put("gpu.memory.used", available(
        "gpu.memory.used", "GPU memory used", sample.memoryUsedMegabytes(), "megabytes", sampledAt));
    readings.put("gpu.memory.total", available(
        "gpu.memory.total", "GPU memory total", sample.memoryTotalMegabytes(), "megabytes", sampledAt));
    readings.put("gpu.power.draw", available(
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
          Double.parseDouble(columns[0]),
          Double.parseDouble(columns[1]),
          Double.parseDouble(columns[2]),
          Double.parseDouble(columns[3]),
          Double.parseDouble(columns[4]));
      if (!sample.isFinite()) {
        throw new IllegalArgumentException("NVIDIA metrics must be finite numbers.");
      }
      return sample;
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("NVIDIA metrics contain a non-numeric value.", failure);
    }
  }

  private static CommandResult runCommand(List<String> command, Duration timeout)
      throws IOException, InterruptedException {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.destroyForcibly();
      return new CommandResult(-1, "", true);
    }
    return new CommandResult(
        process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8), false);
  }

  private static MetricReading available(
      String key, String label, double value, String unit, Instant sampledAt) {
    return new MetricReading(key, label, value, unit, MetricStatus.AVAILABLE, sampledAt, null);
  }

  record NvidiaSample(
      double utilizationPercent,
      double temperatureCelsius,
      double memoryUsedMegabytes,
      double memoryTotalMegabytes,
      double powerDrawWatts) {
    private boolean isFinite() {
      return Double.isFinite(utilizationPercent)
          && Double.isFinite(temperatureCelsius)
          && Double.isFinite(memoryUsedMegabytes)
          && Double.isFinite(memoryTotalMegabytes)
          && Double.isFinite(powerDrawWatts);
    }
  }

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

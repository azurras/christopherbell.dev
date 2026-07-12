package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricReading;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import oshi.spi.SystemInfoProvider;

/** Reads CPU, memory, disk, and network metrics from the shared OSHI provider. */
@Component
public class OshiHostMetricsProvider implements HostMetricsProvider {
  private final SystemInfoProvider systemInfo;
  private long[] previousCpuTicks;
  private Instant previousNetworkSample;
  private long previousBytesReceived;
  private long previousBytesSent;
  private Instant previousDiskSample;
  private long previousDiskBytes;

  public OshiHostMetricsProvider(SystemInfoProvider systemInfo) {
    this.systemInfo = systemInfo;
  }

  @Override
  public synchronized Map<String, MetricReading> read(Instant sampledAt) {
    var hardware = systemInfo.getHardware();
    var processor = hardware.getProcessor();
    var readings = new LinkedHashMap<String, MetricReading>();

    var currentTicks = processor.getSystemCpuLoadTicks();
    if (previousCpuTicks == null) {
      readings.put("cpu.usage", unavailable(
          "cpu.usage", "CPU usage", "percent", sampledAt, "Waiting for a second CPU sample"));
    } else {
      readings.put("cpu.usage", available(
          "cpu.usage",
          "CPU usage",
          processor.getSystemCpuLoadBetweenTicks(previousCpuTicks, currentTicks) * 100,
          "percent",
          sampledAt));
    }
    previousCpuTicks = currentTicks.clone();

    var memory = hardware.getMemory();
    var totalMemory = memory.getTotal();
    readings.put(
        "memory.used",
        totalMemory <= 0
            ? unavailable("memory.used", "Memory used", "percent", sampledAt, "Memory total unavailable")
            : available(
                "memory.used",
                "Memory used",
                percent(totalMemory - memory.getAvailable(), totalMemory),
                "percent",
                sampledAt));
    readings.put(
        "memory.used.bytes",
        totalMemory <= 0
            ? unavailable("memory.used.bytes", "Memory used", "bytes", sampledAt,
                "Memory total unavailable")
            : available("memory.used.bytes", "Memory used", totalMemory - memory.getAvailable(),
                "bytes", sampledAt));
    readings.put(
        "memory.total.bytes",
        totalMemory <= 0
            ? unavailable("memory.total.bytes", "Memory total", "bytes", sampledAt,
                "Memory total unavailable")
            : available("memory.total.bytes", "Memory total", totalMemory, "bytes", sampledAt));

    var stores = systemInfo.getOperatingSystem().getFileSystem().getFileStores(true);
    long totalDisk = 0;
    long usableDisk = 0;
    for (var store : stores) {
      if (store.isLocal()) {
        totalDisk += Math.max(0, store.getTotalSpace());
        usableDisk += Math.max(0, store.getUsableSpace());
      }
    }
    readings.put(
        "disk.free",
        totalDisk <= 0
            ? unavailable("disk.free", "Disk free", "percent", sampledAt, "Local disk total unavailable")
            : available("disk.free", "Disk free", percent(usableDisk, totalDisk), "percent", sampledAt));
    readings.put(
        "disk.free.bytes",
        totalDisk <= 0
            ? unavailable("disk.free.bytes", "Disk free", "bytes", sampledAt,
                "Local disk total unavailable")
            : available("disk.free.bytes", "Disk free", usableDisk, "bytes", sampledAt));
    readings.put(
        "disk.used.bytes",
        totalDisk <= 0
            ? unavailable("disk.used.bytes", "Disk used", "bytes", sampledAt,
                "Local disk total unavailable")
            : available("disk.used.bytes", "Disk used", totalDisk - usableDisk, "bytes", sampledAt));
    long diskBytes = 0;
    for (var disk : hardware.getDiskStores()) {
      diskBytes += Math.max(0, disk.getReadBytes()) + Math.max(0, disk.getWriteBytes());
    }
    var diskElapsed = previousDiskSample == null
        ? Duration.ZERO : Duration.between(previousDiskSample, sampledAt);
    readings.put(
        "disk.activity",
        diskElapsed.isZero() || diskElapsed.isNegative()
            ? unavailable("disk.activity", "Disk activity", "bytes/second", sampledAt,
                "Waiting for a second disk sample")
            : available("disk.activity", "Disk activity",
                Math.max(0, diskBytes - previousDiskBytes)
                    / (diskElapsed.toNanos() / 1_000_000_000.0),
                "bytes/second", sampledAt));
    previousDiskSample = sampledAt;
    previousDiskBytes = diskBytes;

    var systemUptime = systemInfo.getOperatingSystem().getSystemUptime();
    readings.put(
        "system.uptime",
        systemUptime < 0
            ? unavailable("system.uptime", "System uptime", "seconds", sampledAt, "System uptime unavailable")
            : available("system.uptime", "System uptime", systemUptime, "seconds", sampledAt));

    long bytesReceived = 0;
    long bytesSent = 0;
    for (var network : hardware.getNetworkIFs(true)) {
      bytesReceived += Math.max(0, network.getBytesRecv());
      bytesSent += Math.max(0, network.getBytesSent());
    }
    appendNetworkReadings(readings, sampledAt, bytesReceived, bytesSent);
    return Map.copyOf(readings);
  }

  private void appendNetworkReadings(
      Map<String, MetricReading> readings, Instant sampledAt, long bytesReceived, long bytesSent) {
    var elapsed = previousNetworkSample == null
        ? Duration.ZERO
        : Duration.between(previousNetworkSample, sampledAt);
    if (elapsed.isZero() || elapsed.isNegative()) {
      readings.put("network.receive", unavailable(
          "network.receive", "Network received", "bytes/second", sampledAt, "Waiting for a second network sample"));
      readings.put("network.transmit", unavailable(
          "network.transmit", "Network transmitted", "bytes/second", sampledAt, "Waiting for a second network sample"));
    } else {
      var elapsedSeconds = elapsed.toNanos() / 1_000_000_000.0;
      readings.put("network.receive", available(
          "network.receive",
          "Network received",
          Math.max(0, bytesReceived - previousBytesReceived) / elapsedSeconds,
          "bytes/second",
          sampledAt));
      readings.put("network.transmit", available(
          "network.transmit",
          "Network transmitted",
          Math.max(0, bytesSent - previousBytesSent) / elapsedSeconds,
          "bytes/second",
          sampledAt));
    }
    previousNetworkSample = sampledAt;
    previousBytesReceived = bytesReceived;
    previousBytesSent = bytesSent;
  }

  private static double percent(long part, long total) {
    return Math.max(0, Math.min(100, part * 100.0 / total));
  }

  private static MetricReading available(
      String key, String label, double value, String unit, Instant sampledAt) {
    return new MetricReading(key, label, value, unit, MetricStatus.AVAILABLE, sampledAt, null);
  }

  private static MetricReading unavailable(
      String key, String label, String unit, Instant sampledAt, String detail) {
    return new MetricReading(key, label, null, unit, MetricStatus.UNAVAILABLE, sampledAt, detail);
  }
}

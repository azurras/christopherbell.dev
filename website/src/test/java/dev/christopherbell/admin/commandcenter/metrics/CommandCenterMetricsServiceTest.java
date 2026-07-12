package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricReading;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.Sensors;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import oshi.spi.SystemInfoProvider;

class CommandCenterMetricsServiceTest {
  private static final Instant START = Instant.parse("2026-07-12T12:00:00Z");

  @Test
  void oshiProviderUsesInitialTicksAsBaselineAndRejectsUnavailableTemperatures() {
    var fixture = new OshiFixture();
    var provider = new OshiHostMetricsProvider(fixture.systemInfo);
    when(fixture.processor.getSystemCpuLoadTicks())
        .thenReturn(new long[] {1, 2, 3, 4, 5, 6, 7, 8})
        .thenReturn(new long[] {2, 3, 4, 5, 6, 7, 8, 9});
    when(fixture.processor.getSystemCpuLoadBetweenTicks(any(long[].class), any(long[].class)))
        .thenReturn(0.42);
    when(fixture.sensors.getCpuTemperature()).thenReturn(0.0, Double.NaN);

    var first = provider.read(START);
    var second = provider.read(START.plusSeconds(5));

    assertThat(first.get("cpu.usage").status()).isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(first.get("cpu.temperature").status()).isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(second.get("cpu.usage").value()).isEqualTo(42.0);
    assertThat(second.get("cpu.temperature").status()).isEqualTo(MetricStatus.UNAVAILABLE);
  }

  @Test
  void oshiProviderReadsRamDiskAndNetworkDeltas() {
    var fixture = new OshiFixture();
    var provider = new OshiHostMetricsProvider(fixture.systemInfo);
    when(fixture.processor.getSystemCpuLoadTicks())
        .thenReturn(new long[] {1, 1, 1, 1, 1, 1, 1, 1})
        .thenReturn(new long[] {2, 2, 2, 2, 2, 2, 2, 2});
    when(fixture.processor.getSystemCpuLoadBetweenTicks(any(long[].class), any(long[].class)))
        .thenReturn(0.25);
    when(fixture.memory.getTotal()).thenReturn(1_000L);
    when(fixture.memory.getAvailable()).thenReturn(250L);
    when(fixture.fileStore.getTotalSpace()).thenReturn(2_000L);
    when(fixture.fileStore.getUsableSpace()).thenReturn(400L);
    when(fixture.network.getBytesRecv()).thenReturn(10_000L, 15_000L);
    when(fixture.network.getBytesSent()).thenReturn(20_000L, 22_500L);

    var first = provider.read(START);
    var second = provider.read(START.plusSeconds(5));

    assertThat(first.get("network.receive").status()).isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(second.get("memory.used").value()).isEqualTo(75.0);
    assertThat(second.get("disk.free").value()).isEqualTo(20.0);
    assertThat(second.get("network.receive").value()).isEqualTo(1_000.0);
    assertThat(second.get("network.transmit").value()).isEqualTo(500.0);
  }

  @Test
  void collectorIsolatesProviderFailureAndRetainsItsLastGoodValuesAsStale() {
    var clock = new MutableClock(START);
    var properties = properties();
    var successful = new SequenceProvider(reading("cpu.usage", 20.0), new IllegalStateException("sensor failed"));
    HostMetricsProvider independent = sampledAt -> Map.of("gpu.temperature", reading("gpu.temperature", 45.0, sampledAt));
    var service = new CommandCenterMetricsService(
        List.of(successful, independent), properties, clock, "test-version", timeout -> true);

    service.collect();
    clock.advance(Duration.ofSeconds(5));
    service.collect();

    var snapshot = service.snapshot();
    assertThat(metric(snapshot, "cpu.usage").status()).isEqualTo(MetricStatus.STALE);
    assertThat(metric(snapshot, "cpu.usage").value()).isEqualTo(20.0);
    assertThat(metric(snapshot, "gpu.temperature").status()).isEqualTo(MetricStatus.AVAILABLE);
    assertThat(snapshot.alerts()).extracting(CommandCenterSnapshot.Alert::code).contains("PROVIDER_ERROR");
  }

  @Test
  void collectorEvaluatesThresholdsAndEvictsHistoryOutsideWindow() {
    var clock = new MutableClock(START);
    var properties = properties();
    properties.setHistoryDuration(Duration.ofSeconds(10));
    HostMetricsProvider provider = sampledAt -> Map.of(
        "cpu.usage", reading("cpu.usage", 95.0, sampledAt),
        "cpu.temperature", reading("cpu.temperature", 90.0, sampledAt),
        "gpu.temperature", reading("gpu.temperature", 85.0, sampledAt),
        "disk.free", reading("disk.free", 5.0, sampledAt));
    var service = new CommandCenterMetricsService(
        List.of(provider), properties, clock, "test-version", timeout -> true);

    service.collect();
    clock.advance(Duration.ofSeconds(5));
    service.collect();
    clock.advance(Duration.ofSeconds(6));
    service.collect();

    var snapshot = service.snapshot();
    assertThat(snapshot.history().get("cpu.usage"))
        .extracting(CommandCenterSnapshot.MetricPoint::sampledAt)
        .containsExactly(START.plusSeconds(5), START.plusSeconds(11));
    assertThat(snapshot.alerts()).extracting(CommandCenterSnapshot.Alert::code)
        .containsExactlyInAnyOrder(
            "CPU_USAGE_HIGH", "CPU_TEMPERATURE_HIGH", "GPU_TEMPERATURE_HIGH", "DISK_SPACE_LOW");
    assertThat(snapshot.health()).isEqualTo(CommandCenterSnapshot.HealthStatus.DEGRADED);
    assertThat(snapshot.applicationVersion()).isEqualTo("test-version");
    assertThat(snapshot.applicationUptimeSeconds()).isEqualTo(11);
  }

  @Test
  void snapshotMarksLastGoodReadingsStaleAfterTwoSampleIntervals() {
    var clock = new MutableClock(START);
    var properties = properties();
    var service = new CommandCenterMetricsService(
        List.of(sampledAt -> Map.of("cpu.usage", reading("cpu.usage", 10.0, sampledAt))),
        properties,
        clock,
        "test-version",
        timeout -> true);
    service.collect();

    clock.advance(Duration.ofSeconds(11));

    assertThat(metric(service.snapshot(), "cpu.usage").status()).isEqualTo(MetricStatus.STALE);
  }

  @Test
  void serializedSnapshotKeepsUnavailableNullExplicitWithoutLeakingHostPaths() throws Exception {
    var clock = new MutableClock(START);
    HostMetricsProvider provider = sampledAt -> Map.of(
        "cpu.temperature",
        new MetricReading("cpu.temperature", "CPU temperature", null, "celsius", MetricStatus.UNAVAILABLE,
            sampledAt, "Sensor unavailable"));
    var service = new CommandCenterMetricsService(
        List.of(provider), properties(), clock, "test-version", timeout -> false);
    service.collect();

    var json = new ObjectMapper().writeValueAsString(service.snapshot());

    assertThat(json).contains("\"value\":null", "\"status\":\"UNAVAILABLE\"");
    assertThat(json).doesNotContain("nvidia-smi", "shutdown.exe", "logPath", "winSwExecutable");
  }

  private static CommandCenterProperties properties() {
    var properties = new CommandCenterProperties();
    properties.setSampleInterval(Duration.ofSeconds(5));
    properties.setHistoryDuration(Duration.ofMinutes(15));
    return properties;
  }

  private static MetricReading reading(String key, double value) {
    return reading(key, value, START);
  }

  private static MetricReading reading(String key, double value, Instant sampledAt) {
    return new MetricReading(key, key, value, "percent", MetricStatus.AVAILABLE, sampledAt, null);
  }

  private static MetricReading metric(CommandCenterSnapshot snapshot, String key) {
    return snapshot.metrics().stream().filter(metric -> metric.key().equals(key)).findFirst().orElseThrow();
  }

  private static final class SequenceProvider implements HostMetricsProvider {
    private final Object[] results;
    private int index;

    private SequenceProvider(Object... results) {
      this.results = results;
    }

    @Override
    public Map<String, MetricReading> read(Instant sampledAt) {
      var result = results[index++];
      if (result instanceof RuntimeException failure) {
        throw failure;
      }
      var reading = (MetricReading) result;
      return Map.of(reading.key(), new MetricReading(
          reading.key(), reading.label(), reading.value(), reading.unit(), reading.status(), sampledAt, reading.detail()));
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private static final class OshiFixture {
    private final SystemInfoProvider systemInfo = mock(SystemInfoProvider.class);
    private final HardwareAbstractionLayer hardware = mock(HardwareAbstractionLayer.class);
    private final CentralProcessor processor = mock(CentralProcessor.class);
    private final GlobalMemory memory = mock(GlobalMemory.class);
    private final Sensors sensors = mock(Sensors.class);
    private final OperatingSystem operatingSystem = mock(OperatingSystem.class);
    private final FileSystem fileSystem = mock(FileSystem.class);
    private final OSFileStore fileStore = mock(OSFileStore.class);
    private final NetworkIF network = mock(NetworkIF.class);

    private OshiFixture() {
      when(systemInfo.getHardware()).thenReturn(hardware);
      when(systemInfo.getOperatingSystem()).thenReturn(operatingSystem);
      when(hardware.getProcessor()).thenReturn(processor);
      when(hardware.getMemory()).thenReturn(memory);
      when(hardware.getSensors()).thenReturn(sensors);
      when(hardware.getNetworkIFs(true)).thenReturn(List.of(network));
      when(operatingSystem.getFileSystem()).thenReturn(fileSystem);
      when(fileSystem.getFileStores(true)).thenReturn(List.of(fileStore));
      when(fileStore.isLocal()).thenReturn(true);
    }
  }
}

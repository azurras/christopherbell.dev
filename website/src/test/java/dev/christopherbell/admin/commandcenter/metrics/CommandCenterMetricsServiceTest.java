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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.HWDiskStore;
import oshi.hardware.NetworkIF;
import oshi.hardware.Sensors;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import oshi.spi.SystemInfoProvider;

class CommandCenterMetricsServiceTest {
  private static final Instant START = Instant.parse("2026-07-12T12:00:00Z");

  @Test
  void oshiProviderUsesInitialTicksAsBaselineAndLeavesTemperatureToWindowsProvider() {
    var fixture = new OshiFixture();
    var provider = new OshiHostMetricsProvider(fixture.systemInfo);
    when(fixture.processor.getSystemCpuLoadTicks())
        .thenReturn(new long[] {1, 2, 3, 4, 5, 6, 7, 8})
        .thenReturn(new long[] {2, 3, 4, 5, 6, 7, 8, 9});
    when(fixture.processor.getSystemCpuLoadBetweenTicks(any(long[].class), any(long[].class)))
        .thenReturn(0.42);

    var first = provider.read(START);
    var second = provider.read(START.plusSeconds(5));

    assertThat(first.get("cpu.usage").status()).isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(first).doesNotContainKey("cpu.temperature");
    assertThat(second.get("cpu.usage").value()).isEqualTo(42.0);
    assertThat(second).doesNotContainKey("cpu.temperature");
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
    when(fixture.disk.getReadBytes()).thenReturn(1_000L, 4_000L);
    when(fixture.disk.getWriteBytes()).thenReturn(2_000L, 3_500L);

    var first = provider.read(START);
    var second = provider.read(START.plusSeconds(5));

    assertThat(first.get("network.receive").status()).isEqualTo(MetricStatus.UNAVAILABLE);
    assertThat(second.get("memory.used").value()).isEqualTo(75.0);
    assertThat(second.get("disk.free").value()).isEqualTo(20.0);
    assertThat(second.get("network.receive").value()).isEqualTo(1_000.0);
    assertThat(second.get("network.transmit").value()).isEqualTo(500.0);
    assertThat(second.get("memory.used.bytes").value()).isEqualTo(750.0);
    assertThat(second.get("memory.total.bytes").value()).isEqualTo(1_000.0);
    assertThat(second.get("disk.used.bytes").value()).isEqualTo(1_600.0);
    assertThat(second.get("disk.free.bytes").value()).isEqualTo(400.0);
    assertThat(second.get("disk.activity").value()).isEqualTo(900.0);
  }

  @Test
  void providerTimeoutDoesNotBlockOtherProvidersOrTheCollector() throws Exception {
    var clock = new MutableClock(START);
    var properties = properties();
    properties.setProviderTimeout(Duration.ofMillis(100));
    var neverReturns = new CountDownLatch(1);
    HostMetricsProvider blocked = sampledAt -> {
      try {
        neverReturns.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
      return Map.of();
    };
    HostMetricsProvider healthy = sampledAt -> Map.of(
        "gpu.temperature", reading("gpu.temperature", 40, sampledAt));
    var executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("metrics-test-", 0).factory());
    try {
      var service = new CommandCenterMetricsService(
          List.of(blocked, healthy), properties, clock, "test-version", timeout -> true, executor,
          () -> java.util.Optional.empty());

      long started = System.nanoTime();
      service.collect();

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
      assertThat(metric(service.snapshot(), "gpu.temperature").status())
          .isEqualTo(MetricStatus.AVAILABLE);
      assertThat(service.snapshot().alerts()).extracting(CommandCenterSnapshot.Alert::code)
          .contains("PROVIDER_TIMEOUT");
    } finally {
      neverReturns.countDown();
      executor.shutdownNow();
      executor.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test
  void timedOutInterruptIgnoringProviderIsNotResubmittedUntilItsInvocationCompletes() throws Exception {
    var clock = new MutableClock(START);
    var properties = properties();
    properties.setProviderTimeout(Duration.ofMillis(50));
    var release = new CountDownLatch(1);
    var completed = new CountDownLatch(1);
    var invocations = new java.util.concurrent.atomic.AtomicInteger();
    HostMetricsProvider blocked = sampledAt -> {
      invocations.incrementAndGet();
      while (release.getCount() > 0) {
        try { release.await(); } catch (InterruptedException ignored) { }
      }
      completed.countDown();
      return Map.of("cpu.usage", reading("cpu.usage", 10, sampledAt));
    };
    var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    try {
      var service = new CommandCenterMetricsService(
          List.of(blocked), properties, clock, "test", timeout -> true, executor,
          java.util.Optional::empty);
      service.collect();
      clock.advance(Duration.ofSeconds(5));
      service.collect();
      assertThat(invocations).hasValue(1);
      assertThat(service.snapshot().alerts()).extracting(CommandCenterSnapshot.Alert::code)
          .contains("PROVIDER_IN_FLIGHT");

      release.countDown();
      assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
      service.collect();
      assertThat(invocations).hasValue(2);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void stoppedProductionServiceCreatesAlertAndDegradedHealth() {
    HostMetricsProvider provider = sampledAt -> Map.of(
        "production.service.running", new MetricReading(
            "production.service.running", "Production service", 0.0, "state",
            MetricStatus.AVAILABLE, sampledAt, null));
    var service = new CommandCenterMetricsService(
        List.of(provider), properties(), new MutableClock(START), "test", timeout -> true);

    service.collect();

    assertThat(service.snapshot().health()).isEqualTo(CommandCenterSnapshot.HealthStatus.DEGRADED);
    assertThat(service.snapshot().alerts()).extracting(CommandCenterSnapshot.Alert::code)
        .contains("PRODUCTION_SERVICE_STOPPED");
  }

  @Test
  void snapshotComposesPendingActionAndRestoresUnderlyingHealthAfterCancel() {
    var pending = new java.util.concurrent.atomic.AtomicReference<
        java.util.Optional<CommandCenterSnapshot.PendingAction>>(java.util.Optional.of(
            new CommandCenterSnapshot.PendingAction("RESTART_COMPUTER", START.plusSeconds(60), true)));
    var executor = Executors.newSingleThreadExecutor();
    var service = new CommandCenterMetricsService(
        List.of(sampledAt -> Map.of("cpu.usage", reading("cpu.usage", 10, sampledAt))),
        properties(), new MutableClock(START), "test-version", timeout -> true,
        executor, pending::get);
    try {
      service.collect();
      assertThat(service.snapshot().health()).isEqualTo(CommandCenterSnapshot.HealthStatus.ACTION_PENDING);
      assertThat(service.snapshot().pendingAction()).isEqualTo(pending.get().orElseThrow());

      pending.set(java.util.Optional.empty());
      assertThat(service.snapshot().health()).isEqualTo(CommandCenterSnapshot.HealthStatus.HEALTHY);
      assertThat(service.snapshot().pendingAction()).isNull();
    } finally {
      // The test owns this executor; production owns and closes its bean.
      executor.shutdownNow();
    }
  }

  @Test
  void oshiProviderPublishesHostUptimeSeparatelyFromApplicationUptime() {
    var fixture = new OshiFixture();
    when(fixture.operatingSystem.getSystemUptime()).thenReturn(7_200L);
    when(fixture.processor.getSystemCpuLoadTicks()).thenReturn(new long[] {1, 1, 1, 1, 1, 1, 1, 1});
    var provider = new OshiHostMetricsProvider(fixture.systemInfo);

    var readings = provider.read(START);

    assertThat(readings).containsKey("system.uptime");
    assertThat(readings.get("system.uptime"))
        .extracting(MetricReading::value, MetricReading::unit, MetricReading::status)
        .containsExactly(7_200.0, "seconds", MetricStatus.AVAILABLE);
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
  void providerFailureDetailsAreSanitizedBeforeEnteringTheSnapshot() throws Exception {
    var clock = new MutableClock(START);
    var properties = properties();
    var secret = "C:\\private\\service\\nvidia-smi.exe --secret-token=abc123";
    var provider = new SequenceProvider(reading("cpu.usage", 20.0), new IllegalStateException(secret));
    var service = new CommandCenterMetricsService(
        List.of(provider), properties, clock, "test-version", timeout -> true);
    service.collect();
    clock.advance(Duration.ofSeconds(5));

    service.collect();

    var snapshot = service.snapshot();
    assertThat(metric(snapshot, "cpu.usage").detail()).isEqualTo("Provider temporarily unavailable.");
    assertThat(new ObjectMapper().writeValueAsString(snapshot))
        .doesNotContain("private", "nvidia-smi", "secret-token", "abc123");
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
    private final HWDiskStore disk = mock(HWDiskStore.class);

    private OshiFixture() {
      when(systemInfo.getHardware()).thenReturn(hardware);
      when(systemInfo.getOperatingSystem()).thenReturn(operatingSystem);
      when(hardware.getProcessor()).thenReturn(processor);
      when(hardware.getMemory()).thenReturn(memory);
      when(hardware.getSensors()).thenReturn(sensors);
      when(hardware.getNetworkIFs(true)).thenReturn(List.of(network));
      when(hardware.getDiskStores()).thenReturn(List.of(disk));
      when(operatingSystem.getFileSystem()).thenReturn(fileSystem);
      when(fileSystem.getFileStores(true)).thenReturn(List.of(fileStore));
      when(fileStore.isLocal()).thenReturn(true);
    }
  }
}

package dev.christopherbell.admin.commandcenter.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.OptionalDouble;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LibreHardwareCpuTemperatureClientTest {
  @Test
  void firstReadReturnsImmediatelyAndSchedulesOnlyOneRefresh() {
    var executor = new ManualExecutorService();
    var probe = new FakeProbe(OptionalDouble.of(64.25));
    var client = client(probe, executor, new MutableClock());

    assertThat(client.readCelsius()).isEmpty();
    assertThat(client.readCelsius()).isEmpty();
    assertThat(executor.pending()).isEqualTo(1);

    executor.runNext();

    assertThat(client.readCelsius()).hasValue(64.25);
    assertThat(executor.pending()).isZero();
  }

  @Test
  void refreshIsThrottledAndRetainsLastGoodAcrossFailure() {
    var clock = new MutableClock();
    var executor = new ManualExecutorService();
    var probe = new FakeProbe(OptionalDouble.of(61), OptionalDouble.empty());
    var client = client(probe, executor, clock);

    client.readCelsius();
    executor.runNext();
    clock.advance(Duration.ofSeconds(29));

    assertThat(client.readCelsius()).hasValue(61);
    assertThat(executor.pending()).isZero();

    clock.advance(Duration.ofSeconds(1));
    assertThat(client.readCelsius()).hasValue(61);
    assertThat(executor.pending()).isEqualTo(1);
    executor.runNext();

    assertThat(client.readCelsius()).hasValue(61);
    assertThat(probe.readCalls()).isEqualTo(2);
  }

  @Test
  void lastGoodExpiresAfterTwoRefreshIntervalsWithoutAnotherSuccess() {
    var clock = new MutableClock();
    var executor = new ManualExecutorService();
    var probe = new FakeProbe(OptionalDouble.of(61), OptionalDouble.empty());
    var client = client(probe, executor, clock);

    client.readCelsius();
    executor.runNext();
    assertThat(client.readCelsius()).hasValue(61);

    clock.advance(Duration.ofSeconds(61));

    assertThat(client.readCelsius()).isEmpty();
  }

  @Test
  void unsupportedPlatformNeverSchedulesTheProbe() {
    var executor = new ManualExecutorService();
    var probe = new FakeProbe(OptionalDouble.of(60));
    var client = new LibreHardwareCpuTemperatureClient(
        probe, executor, new MutableClock(), Duration.ofSeconds(30), false);

    assertThat(client.readCelsius()).isEmpty();
    assertThat(executor.pending()).isZero();
    assertThat(probe.readCalls()).isZero();
  }

  @Test
  void probeFailureDegradesWithoutEscapingTheRefreshTask() {
    var executor = new ManualExecutorService();
    var probe = new ThrowingProbe();
    var client = client(probe, executor, new MutableClock());

    assertThat(client.readCelsius()).isEmpty();
    executor.runNext();

    assertThat(client.readCelsius()).isEmpty();
  }

  @Test
  void closeIsIdempotentAndClosesProbeAndExecutorOnce() {
    var executor = new ManualExecutorService();
    var probe = new FakeProbe(OptionalDouble.empty());
    var client = client(probe, executor, new MutableClock());
    client.readCelsius();

    client.close();
    client.close();

    assertThat(executor.isShutdown()).isTrue();
    assertThat(probe.closeCalls()).isEqualTo(1);
  }

  private static LibreHardwareCpuTemperatureClient client(
      LibreHardwareCpuTemperatureClient.TemperatureProbe probe,
      ManualExecutorService executor,
      Clock clock) {
    return new LibreHardwareCpuTemperatureClient(
        probe, executor, clock, Duration.ofSeconds(30), true);
  }

  private static final class FakeProbe
      implements LibreHardwareCpuTemperatureClient.TemperatureProbe {
    private final ArrayDeque<OptionalDouble> values = new ArrayDeque<>();
    private int readCalls;
    private int closeCalls;

    private FakeProbe(OptionalDouble... values) {
      this.values.addAll(java.util.List.of(values));
    }

    @Override
    public OptionalDouble readCelsius() {
      readCalls++;
      return values.isEmpty() ? OptionalDouble.empty() : values.remove();
    }

    @Override
    public void close() {
      closeCalls++;
    }

    int readCalls() { return readCalls; }
    int closeCalls() { return closeCalls; }
  }

  private static final class ThrowingProbe
      implements LibreHardwareCpuTemperatureClient.TemperatureProbe {
    @Override public OptionalDouble readCelsius() { throw new IllegalStateException("failed"); }
    @Override public void close() { }
  }

  private static final class ManualExecutorService extends AbstractExecutorService {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    @Override public void execute(Runnable command) {
      if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
      tasks.add(command);
    }

    void runNext() { tasks.remove().run(); }
    int pending() { return tasks.size(); }
    @Override public void shutdown() { shutdown = true; }
    @Override public java.util.List<Runnable> shutdownNow() {
      shutdown = true;
      var pending = java.util.List.copyOf(tasks);
      tasks.clear();
      return pending;
    }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
  }

  private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-07-13T01:00:00Z");
    void advance(Duration duration) { instant = instant.plus(duration); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return instant; }
  }
}

package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Returns cached CPU temperature while refreshing the privileged probe off the sampling path. */
@Component
public final class LibreHardwareCpuTemperatureClient implements CpuTemperatureSensorClient {
  private static final Duration FAILURE_RETRY_INTERVAL = Duration.ofMinutes(5);
  private final TemperatureProbe probe;
  private final ExecutorService refreshExecutor;
  private final Clock clock;
  private final Duration refreshInterval;
  private final boolean windows;
  private final AtomicReference<CachedTemperature> lastGood =
      new AtomicReference<>(new CachedTemperature(OptionalDouble.empty(), Instant.MIN));
  private final AtomicBoolean refreshInFlight = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Instant nextRefresh = Instant.MIN;

  @Autowired
  public LibreHardwareCpuTemperatureClient(
      PowerShellCpuTemperatureProbe probe,
      CommandCenterProperties properties,
      Clock clock) {
    this(
        probe,
        Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("cpu-temperature-refresh-", 0).factory()),
        clock,
        properties.getCpuTemperatureRefreshInterval(),
        properties.isSensorLibrariesEnabled()
            && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
  }

  LibreHardwareCpuTemperatureClient(
      TemperatureProbe probe,
      ExecutorService refreshExecutor,
      Clock clock,
      Duration refreshInterval,
      boolean windows) {
    this.probe = probe;
    this.refreshExecutor = refreshExecutor;
    this.clock = clock;
    this.refreshInterval = refreshInterval;
    this.windows = windows;
  }

  @Override
  public OptionalDouble readCelsius() {
    var current = currentValue();
    if (windows
        && !closed.get()
        && !clock.instant().isBefore(nextRefresh)
        && refreshInFlight.compareAndSet(false, true)) {
      try {
        refreshExecutor.submit(this::refresh);
      } catch (RejectedExecutionException failure) {
        refreshInFlight.set(false);
      }
    }
    return current;
  }

  private void refresh() {
    Duration nextDelay = refreshInterval;
    try {
      var value = probe.readCelsius();
      if (value.isPresent()) {
        lastGood.set(new CachedTemperature(value, clock.instant()));
      } else {
        nextDelay = FAILURE_RETRY_INTERVAL;
      }
    } catch (RuntimeException ignored) {
      nextDelay = FAILURE_RETRY_INTERVAL;
      // The provider preserves the prior good value and explicit unavailable semantics.
    } finally {
      nextRefresh = clock.instant().plus(nextDelay);
      refreshInFlight.set(false);
    }
  }

  @Override
  @PreDestroy
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    refreshExecutor.shutdownNow();
    probe.close();
  }

  interface TemperatureProbe extends AutoCloseable {
    OptionalDouble readCelsius();
    @Override void close();
  }

  private OptionalDouble currentValue() {
    var cached = lastGood.get();
    if (cached.value().isEmpty()
        || cached.sampledAt().plus(refreshInterval.multipliedBy(2)).isBefore(clock.instant())) {
      return OptionalDouble.empty();
    }
    return cached.value();
  }

  private record CachedTemperature(OptionalDouble value, Instant sampledAt) {}
}

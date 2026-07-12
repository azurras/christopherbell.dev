package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.Alert;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.HealthStatus;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricPoint;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricReading;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.MetricStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Collects host metrics into one atomic snapshot with bounded in-memory history. */
@Service
public class CommandCenterMetricsService {
  private static final Logger LOGGER = LoggerFactory.getLogger(CommandCenterMetricsService.class);
  private static final String PROVIDER_UNAVAILABLE_DETAIL = "Provider temporarily unavailable.";
  private final List<HostMetricsProvider> providers;
  private final CommandCenterProperties properties;
  private final Clock clock;
  private final String applicationVersion;
  private final MongoPing mongoPing;
  private final ExecutorService providerExecutor;
  private final PendingActionSupplier pendingActionSupplier;
  private final Instant applicationStartedAt;
  private final AtomicReference<CommandCenterSnapshot> latest;
  private final Map<String, ArrayDeque<MetricPoint>> history = new TreeMap<>();
  private final Map<HostMetricsProvider, Map<String, MetricReading>> lastGoodByProvider =
      new IdentityHashMap<>();
  private final Map<HostMetricsProvider, ProviderTask> inFlightProviders = new IdentityHashMap<>();

  /** Creates the production collector without performing any host or database I/O at startup. */
  @Autowired
  public CommandCenterMetricsService(
      List<HostMetricsProvider> providers,
      CommandCenterProperties properties,
      Clock clock,
      MongoTemplate mongoTemplate,
      ObjectProvider<BuildProperties> buildProperties,
      @Qualifier("commandCenterProviderExecutor") ExecutorService providerExecutor,
      CommandCenterActionService actionService) {
    this(
        providers,
        properties,
        clock,
        applicationVersion(buildProperties),
        timeout -> boundedMongoPing(mongoTemplate, timeout),
        providerExecutor,
        actionService::pendingAction);
  }

  CommandCenterMetricsService(
      List<HostMetricsProvider> providers,
      CommandCenterProperties properties,
      Clock clock,
      String applicationVersion,
      MongoPing mongoPing) {
    this(providers, properties, clock, applicationVersion, mongoPing,
        java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("command-center-provider-test-", 0).factory()),
        Optional::empty);
  }

  CommandCenterMetricsService(
      List<HostMetricsProvider> providers,
      CommandCenterProperties properties,
      Clock clock,
      String applicationVersion,
      MongoPing mongoPing,
      ExecutorService providerExecutor,
      PendingActionSupplier pendingActionSupplier) {
    this.providers = List.copyOf(providers);
    this.properties = properties;
    this.clock = clock;
    this.applicationVersion = applicationVersion == null ? "unknown" : applicationVersion;
    this.mongoPing = mongoPing;
    this.providerExecutor = providerExecutor;
    this.pendingActionSupplier = pendingActionSupplier;
    this.applicationStartedAt = clock.instant();
    this.latest = new AtomicReference<>(new CommandCenterSnapshot(
        HealthStatus.OFFLINE,
        applicationStartedAt,
        List.of(),
        Map.of(),
        List.of(),
        null,
        this.applicationVersion,
        0));
  }

  /** Samples every provider independently and atomically publishes the resulting snapshot. */
  public synchronized void collect() {
    if (!properties.isEnabled()) {
      return;
    }
    var sampledAt = clock.instant();
    var readings = new LinkedHashMap<String, MetricReading>();
    var alerts = new ArrayList<Alert>();

    inFlightProviders.entrySet().removeIf(entry -> entry.getValue().completed().get());
    var submitted = new LinkedHashMap<HostMetricsProvider, ProviderTask>();
    for (var provider : providers) {
      if (inFlightProviders.containsKey(provider)) {
        retainStale(provider, readings);
        alerts.add(new Alert("PROVIDER_IN_FLIGHT", "WARNING",
            provider.getClass().getSimpleName() + " is still completing its prior sample."));
        continue;
      }
      var started = new AtomicBoolean();
      var completed = new AtomicBoolean();
      var future = providerExecutor.submit(() -> {
        started.set(true);
        try {
          return Map.copyOf(provider.read(sampledAt));
        } finally {
          completed.set(true);
        }
      });
      var task = new ProviderTask(future, System.nanoTime(), started, completed);
      inFlightProviders.put(provider, task);
      submitted.put(provider, task);
    }
    for (var entry : submitted.entrySet()) {
      var provider = entry.getKey();
      try {
        long timeoutNanos = properties.getProviderTimeout().toNanos();
        long remaining = Math.max(1, timeoutNanos - (System.nanoTime() - entry.getValue().startedNanos()));
        var providerReadings = entry.getValue().future().get(remaining, TimeUnit.NANOSECONDS);
        readings.putAll(providerReadings);
        lastGoodByProvider.put(provider, providerReadings);
        inFlightProviders.remove(provider, entry.getValue());
      } catch (TimeoutException failure) {
        cancel(entry.getValue());
        retainStale(provider, readings);
        alerts.add(new Alert(
            "PROVIDER_TIMEOUT", "WARNING",
            provider.getClass().getSimpleName() + " exceeded its sampling timeout."));
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        cancel(entry.getValue());
        retainStale(provider, readings);
        alerts.add(new Alert("PROVIDER_ERROR", "WARNING", "Metrics sampling was interrupted."));
      } catch (Exception failure) {
        inFlightProviders.remove(provider, entry.getValue());
        LOGGER.warn(
            "Command-center metrics provider {} failed.",
            provider.getClass().getName(),
            failure);
        retainStale(provider, readings);
        alerts.add(new Alert(
            "PROVIDER_ERROR",
            "WARNING",
            provider.getClass().getSimpleName() + " could not be sampled."));
      }
    }

    var mongoAvailable = pingMongo();
    readings.put(
        "mongodb.connectivity",
        new MetricReading(
            "mongodb.connectivity",
            "MongoDB connectivity",
            mongoAvailable ? 1.0 : 0.0,
            "state",
            mongoAvailable ? MetricStatus.AVAILABLE : MetricStatus.ERROR,
            sampledAt,
            mongoAvailable ? null : "MongoDB ping failed or timed out"));
    if (!mongoAvailable) {
      alerts.add(new Alert("MONGODB_UNAVAILABLE", "ERROR", "MongoDB did not answer the bounded ping."));
    }

    appendAndTrimHistory(readings, sampledAt.minus(properties.getHistoryDuration()));
    alerts.addAll(thresholdAlerts(readings));
    var immutableReadings = readings.values().stream()
        .sorted(Comparator.comparing(MetricReading::key))
        .toList();
    latest.set(new CommandCenterSnapshot(
        health(immutableReadings, alerts),
        sampledAt,
        immutableReadings,
        copyHistory(),
        List.copyOf(alerts),
        null,
        applicationVersion,
        Math.max(0, Duration.between(applicationStartedAt, sampledAt).toSeconds())));
  }

  /** Returns the cached state, marking values stale when collection has stopped advancing. */
  public CommandCenterSnapshot snapshot() {
    var snapshot = latest.get();
    var cutoff = clock.instant().minus(properties.getSampleInterval().multipliedBy(2));
    var readings = snapshot.metrics().stream()
        .map(reading -> reading.status() == MetricStatus.AVAILABLE && reading.sampledAt().isBefore(cutoff)
            ? stale(reading, "Sample is older than two collection intervals")
            : reading)
        .toList();
    var pending = pendingActionSupplier.pendingAction().orElse(null);
    var underlyingHealth = readings.equals(snapshot.metrics())
        ? snapshot.health() : HealthStatus.DEGRADED;
    if (underlyingHealth == HealthStatus.ACTION_PENDING) {
      underlyingHealth = health(readings, snapshot.alerts());
    }
    return new CommandCenterSnapshot(
        pending == null ? underlyingHealth : HealthStatus.ACTION_PENDING,
        snapshot.sampledAt(),
        readings,
        snapshot.history(),
        snapshot.alerts(),
        pending,
        snapshot.applicationVersion(),
        Math.max(0, Duration.between(applicationStartedAt, clock.instant()).toSeconds()));
  }

  private void retainStale(HostMetricsProvider provider, Map<String, MetricReading> readings) {
    lastGoodByProvider.getOrDefault(provider, Map.of()).forEach((key, reading) ->
        readings.put(key, stale(reading, PROVIDER_UNAVAILABLE_DETAIL)));
  }

  private static void cancel(ProviderTask task) {
    boolean cancelled = task.future().cancel(true);
    if (cancelled && !task.started().get()) {
      task.completed().set(true);
    }
  }

  private boolean pingMongo() {
    try {
      return mongoPing.ping(properties.getProviderTimeout());
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private void appendAndTrimHistory(Map<String, MetricReading> readings, Instant cutoff) {
    readings.forEach((key, reading) -> {
      if (reading.status() == MetricStatus.AVAILABLE && reading.value() != null) {
        history.computeIfAbsent(key, ignored -> new ArrayDeque<>())
            .addLast(new MetricPoint(reading.sampledAt(), reading.value()));
      }
    });
    history.values().forEach(points -> {
      while (!points.isEmpty() && points.getFirst().sampledAt().isBefore(cutoff)) {
        points.removeFirst();
      }
    });
    history.entrySet().removeIf(entry -> entry.getValue().isEmpty());
  }

  private Map<String, List<MetricPoint>> copyHistory() {
    var copy = new TreeMap<String, List<MetricPoint>>();
    history.forEach((key, points) -> copy.put(key, List.copyOf(points)));
    return Map.copyOf(copy);
  }

  private List<Alert> thresholdAlerts(Map<String, MetricReading> readings) {
    var thresholds = properties.getThresholds();
    var alerts = new ArrayList<Alert>();
    addHighAlert(
        alerts, readings.get("cpu.usage"), thresholds.getCpuWarningPercent(),
        "CPU_USAGE_HIGH", "CPU usage is above the warning threshold.");
    addHighAlert(
        alerts, readings.get("cpu.temperature"), thresholds.getCpuTemperatureWarningCelsius(),
        "CPU_TEMPERATURE_HIGH", "CPU temperature is above the warning threshold.");
    addHighAlert(
        alerts, readings.get("gpu.temperature"), thresholds.getGpuTemperatureWarningCelsius(),
        "GPU_TEMPERATURE_HIGH", "GPU temperature is above the warning threshold.");
    var disk = readings.get("disk.free");
    if (isAvailable(disk) && disk.value() <= thresholds.getDiskFreeWarningPercent()) {
      alerts.add(new Alert("DISK_SPACE_LOW", "WARNING", "Free disk space is below the warning threshold."));
    }
    var service = readings.get("production.service.running");
    if (isAvailable(service) && service.value() == 0) {
      alerts.add(new Alert("PRODUCTION_SERVICE_STOPPED", "ERROR",
          "The production website service is stopped."));
    }
    return alerts;
  }

  private static void addHighAlert(
      List<Alert> alerts,
      MetricReading reading,
      double threshold,
      String code,
      String message) {
    if (isAvailable(reading) && reading.value() >= threshold) {
      alerts.add(new Alert(code, "WARNING", message));
    }
  }

  private static boolean isAvailable(MetricReading reading) {
    return reading != null && reading.status() == MetricStatus.AVAILABLE && reading.value() != null;
  }

  private static HealthStatus health(List<MetricReading> readings, List<Alert> alerts) {
    return alerts.isEmpty() && readings.stream().allMatch(reading -> reading.status() == MetricStatus.AVAILABLE)
        ? HealthStatus.HEALTHY
        : HealthStatus.DEGRADED;
  }

  private static MetricReading stale(MetricReading reading, String detail) {
    return new MetricReading(
        reading.key(),
        reading.label(),
        reading.value(),
        reading.unit(),
        MetricStatus.STALE,
        reading.sampledAt(),
        detail == null || detail.isBlank() ? "Provider sample failed" : detail);
  }

  private static boolean boundedMongoPing(MongoTemplate mongoTemplate, Duration timeout) {
    var task = new FutureTask<>(() -> {
      mongoTemplate.executeCommand(new Document("ping", 1));
      return true;
    });
    Thread.ofVirtual().name("command-center-mongodb-ping").start(task);
    try {
      return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      task.cancel(true);
      return false;
    } catch (Exception failure) {
      task.cancel(true);
      return false;
    }
  }

  private static String applicationVersion(ObjectProvider<BuildProperties> buildProperties) {
    var properties = buildProperties.getIfAvailable();
    return properties == null ? "unknown" : properties.getVersion();
  }

  @FunctionalInterface
  interface MongoPing {
    boolean ping(Duration timeout);
  }

  @FunctionalInterface
  interface PendingActionSupplier {
    Optional<CommandCenterSnapshot.PendingAction> pendingAction();
  }

  private record ProviderTask(
      Future<Map<String, MetricReading>> future,
      long startedNanos,
      AtomicBoolean started,
      AtomicBoolean completed) {}
}

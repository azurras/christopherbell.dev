package dev.christopherbell.admin.commandcenter.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable, serialization-safe view of the latest command-center state. */
public record CommandCenterSnapshot(
    HealthStatus health,
    Instant sampledAt,
    List<MetricReading> metrics,
    Map<String, List<MetricPoint>> history,
    List<Alert> alerts,
    PendingAction pendingAction,
    String applicationVersion,
    long applicationUptimeSeconds) {

  /** Overall host and application state shown by command-center clients. */
  public enum HealthStatus {
    HEALTHY,
    DEGRADED,
    ACTION_PENDING,
    OFFLINE
  }

  /** Availability and freshness of an individual reading. */
  public enum MetricStatus {
    AVAILABLE,
    UNAVAILABLE,
    STALE,
    ERROR
  }

  /** One named metric, including explicit unavailable values and diagnostics. */
  public record MetricReading(
      String key,
      String label,
      Double value,
      String unit,
      MetricStatus status,
      Instant sampledAt,
      String detail) {}

  /** One value retained in a metric's bounded in-memory history. */
  public record MetricPoint(Instant sampledAt, Double value) {}

  /** Operator-facing warning derived from current readings. */
  public record Alert(String code, String severity, String message) {}

  /** A confirmed host action waiting for its scheduled execution time. */
  public record PendingAction(String action, Instant executeAt, boolean cancellable) {}
}

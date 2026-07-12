package dev.christopherbell.admin.commandcenter.metrics;

import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot;
import java.time.Instant;
import java.util.Map;

/** Supplies one independently-failable group of host metric readings. */
public interface HostMetricsProvider {

  /** Reads metrics for one collection instant without retaining API-layer state. */
  Map<String, CommandCenterSnapshot.MetricReading> read(Instant sampledAt);
}

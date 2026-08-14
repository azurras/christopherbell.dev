package dev.christopherbell.admin.commandcenter.metrics;

import java.time.Duration;

/** Bounded active-backend connectivity probe for command-center sampling. */
public interface DatabaseConnectivityProbe {
  /** Compatibility default for existing Mongo-era test and transition callers. */
  default String backendName() {
    return "mongodb";
  }

  boolean ping(Duration timeout);
}

package dev.christopherbell.admin.commandcenter.metrics;

import java.time.Duration;

/** Bounded probe for authenticated persistence identity details. */
@FunctionalInterface
public interface PersistenceIdentityProbe {
  PersistenceIdentity identity(Duration timeout);
}

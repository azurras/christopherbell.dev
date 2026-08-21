package dev.christopherbell.configuration.persistence.migration;

/** Write exclusion held continuously across final source verification and target commit. */
interface FinalizationFreezeGuard extends AutoCloseable {
  void requireLocked();

  @Override
  void close();
}

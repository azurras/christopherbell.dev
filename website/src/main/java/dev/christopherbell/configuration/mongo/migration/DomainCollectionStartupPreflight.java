package dev.christopherbell.configuration.mongo.migration;

import java.util.Objects;
import org.springframework.stereotype.Component;

/** Recurring target-release gate executed before the first migration-runner mutation. */
@Component
public final class DomainCollectionStartupPreflight {
  private final DomainCollectionReleaseMetadata metadata;
  private final DomainCollectionCutoverLedger ledger;

  public DomainCollectionStartupPreflight(
      DomainCollectionReleaseMetadata metadata,
      DomainCollectionCutoverLedger ledger) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  /** Rechecks the current durable ledger on every managed target release startup. */
  public void requireReady() {
    if (metadata.requiresTarget()) {
      ledger.requireTargetActive();
    }
  }
}

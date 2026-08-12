package dev.christopherbell.configuration.mongo.migration;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DomainCollectionStartupPreflightTest {
  @Mock private DomainCollectionReleaseMetadata metadata;
  @Mock private DomainCollectionCutoverLedger ledger;

  @Test
  void targetReleaseChecksTheCurrentLedgerOnEveryInvocation() {
    when(metadata.requiresTarget()).thenReturn(true);
    var preflight = new DomainCollectionStartupPreflight(metadata, ledger);

    preflight.requireReady();
    preflight.requireReady();

    verify(ledger, org.mockito.Mockito.times(2)).requireTargetActive();
  }

  @Test
  void unmanagedRuntimePerformsNoLedgerRead() {
    var preflight = new DomainCollectionStartupPreflight(metadata, ledger);

    preflight.requireReady();

    verify(ledger, never()).requireTargetActive();
  }
}

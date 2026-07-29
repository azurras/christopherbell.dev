package dev.christopherbell.admin.commandcenter.action;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PendingActionReconcilerTest {
  @Test
  void applicationReadinessReconcilesElapsedStateAtTheInjectedClock() {
    var now = Instant.parse("2026-07-29T21:00:00Z");
    var store = mock(PendingActionStore.class);
    var reconciler = new PendingActionReconciler(
        store, Clock.fixed(now, ZoneOffset.UTC));

    reconciler.reconcileAtApplicationReadiness();

    verify(store).reconcile(now);
  }
}

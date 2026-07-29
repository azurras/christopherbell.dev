package dev.christopherbell.admin.commandcenter.action;

import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Reconciles elapsed pending-action UI state once application dependencies are ready. */
@Component
class PendingActionReconciler {
  private final PendingActionStore pendingActions;
  private final Clock clock;

  PendingActionReconciler(PendingActionStore pendingActions, Clock clock) {
    this.pendingActions = pendingActions;
    this.clock = clock;
  }

  @EventListener(ApplicationReadyEvent.class)
  void reconcileAtApplicationReadiness() {
    pendingActions.reconcile(clock.instant());
  }
}

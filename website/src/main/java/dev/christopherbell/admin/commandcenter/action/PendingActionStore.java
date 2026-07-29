package dev.christopherbell.admin.commandcenter.action;

import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot.PendingAction;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Atomic persistence boundary for the one pending machine power action. */
public interface PendingActionStore {
  boolean reserve(Reservation reservation, Instant now);

  Optional<Reservation> active(Instant now);

  boolean clear(Reservation reservation);

  void reconcile(Instant now);

  /** Complete identity and deadline of one reserved restart or shutdown. */
  record Reservation(
      CommandCenterActionType action,
      Instant acceptedAt,
      Instant executeAt) {
    public Reservation {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(acceptedAt, "acceptedAt");
      Objects.requireNonNull(executeAt, "executeAt");
      if (action != CommandCenterActionType.RESTART_COMPUTER
          && action != CommandCenterActionType.SHUTDOWN_COMPUTER) {
        throw new IllegalArgumentException("Only machine power actions can be reserved.");
      }
      if (!executeAt.isAfter(acceptedAt)) {
        throw new IllegalArgumentException("Power action deadline must follow acceptance.");
      }
    }

    public PendingAction snapshot() {
      return new PendingAction(action.name(), executeAt, true);
    }
  }
}

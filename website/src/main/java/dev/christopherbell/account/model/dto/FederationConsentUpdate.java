package dev.christopherbell.account.model.dto;

import jakarta.validation.constraints.NotNull;

/** An explicit authenticated choice to enable or disable federation. */
public record FederationConsentUpdate(@NotNull Boolean enabled) {

  public boolean requestedState() {
    return Boolean.TRUE.equals(enabled);
  }
}

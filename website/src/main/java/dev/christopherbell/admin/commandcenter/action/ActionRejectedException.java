package dev.christopherbell.admin.commandcenter.action;

import dev.christopherbell.libs.api.exception.InvalidRequestException;

/** An action confirmation refused for a known reason; callers see only the rejection message. */
final class ActionRejectedException extends InvalidRequestException {
  private final ActionRejection rejection;

  ActionRejectedException(ActionRejection rejection) {
    super(rejection.message());
    this.rejection = rejection;
  }

  ActionRejection rejection() {
    return rejection;
  }
}

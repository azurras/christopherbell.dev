package dev.christopherbell.admin.commandcenter.action;

/** Closed allowlist of command-center actions. */
public enum CommandCenterActionType {
  RESTART_SITE("RESTART SITE", true),
  RESTART_COMPUTER("RESTART COMPUTER", true),
  SHUTDOWN_COMPUTER("SHUTDOWN COMPUTER", true),
  CANCEL_PENDING_ACTION("", false);

  private final String confirmationPhrase;
  private final boolean requiresChallenge;

  CommandCenterActionType(String confirmationPhrase, boolean requiresChallenge) {
    this.confirmationPhrase = confirmationPhrase;
    this.requiresChallenge = requiresChallenge;
  }

  public String getConfirmationPhrase() {
    return confirmationPhrase;
  }

  public boolean isRequiresChallenge() {
    return requiresChallenge;
  }
}

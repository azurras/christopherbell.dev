package dev.christopherbell.admin.commandcenter.action;

/**
 * Why a command-center action confirmation was refused: the message returned to the caller and
 * the outcome recorded in the audit log.
 */
enum ActionRejection {
  WRONG_PASSWORD("Password verification failed.", "wrong-password"),
  PHRASE_MISMATCH("Confirmation phrase did not match.", "phrase-mismatch"),
  THROTTLED("Too many failed action confirmations.", "throttled"),
  COOLDOWN("Action is in cooldown.", "cooldown"),
  ACTION_PENDING("A machine power action is already pending.", "action-pending"),
  CHALLENGE_REQUIRED("A valid action challenge is required.", "invalid-challenge"),
  CHALLENGE_INVALID("Action challenge is invalid or expired.", "invalid-challenge");

  private final String message;
  private final String auditOutcome;

  ActionRejection(String message, String auditOutcome) {
    this.message = message;
    this.auditOutcome = auditOutcome;
  }

  String message() {
    return message;
  }

  String auditOutcome() {
    return auditOutcome;
  }
}

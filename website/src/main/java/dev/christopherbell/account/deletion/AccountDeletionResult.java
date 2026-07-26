package dev.christopherbell.account.deletion;

/** Bounded public result for a completed or resumed account deletion. */
public record AccountDeletionResult(
    String pseudonym,
    AccountDeletionStatus status,
    int completedSteps
) {}

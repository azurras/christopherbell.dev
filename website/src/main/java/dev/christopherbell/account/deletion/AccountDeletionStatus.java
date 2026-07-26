package dev.christopherbell.account.deletion;

/** Durable lifecycle for one account-deletion job. */
public enum AccountDeletionStatus {
  ACTIVE,
  FAILED,
  COMPLETE
}

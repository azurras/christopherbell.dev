package dev.christopherbell.whatsforlunch.restaurant.session;

/** Result of an atomic attempt to add an account to a WFL session. */
public enum SessionJoinOutcome {
  JOINED,
  ALREADY_MEMBER,
  FULL,
  NOT_FOUND
}

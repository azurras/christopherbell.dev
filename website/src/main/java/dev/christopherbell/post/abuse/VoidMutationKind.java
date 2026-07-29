package dev.christopherbell.post.abuse;

/** New-account add actions that receive independent hourly budgets. */
public enum VoidMutationKind {
  ROOT_POST,
  REPLY,
  KEEP_ALIVE,
  FOLLOW
}

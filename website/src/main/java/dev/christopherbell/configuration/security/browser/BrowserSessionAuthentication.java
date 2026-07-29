package dev.christopherbell.configuration.security.browser;

import java.util.Objects;

/** Persisted browser session paired with the current account state that validates it. */
public record BrowserSessionAuthentication(
    BrowserSession session,
    BrowserSessionAccount account) {

  public BrowserSessionAuthentication {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(account, "account");
  }
}

package dev.christopherbell.configuration.security.browser;

import java.util.Optional;

/** Resolves one browser session together with its current account security state. */
public interface BrowserSessionAuthenticationStore {
  /**
   * Loads the session and matching account in one persistence operation.
   *
   * @return the joined authentication state, or empty when either document is unavailable
   */
  Optional<BrowserSessionAuthentication> findById(String sessionId);
}

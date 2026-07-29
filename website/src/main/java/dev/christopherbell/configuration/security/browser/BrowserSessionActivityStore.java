package dev.christopherbell.configuration.security.browser;

import java.time.Instant;
import java.util.Optional;

/** Owns conditional persistence transitions for authenticated browser-session activity. */
public interface BrowserSessionActivityStore {
  /**
   * Records due interactive activity only when the observed live session has not changed.
   *
   * @return the updated session, or empty when the session changed, expired, or was revoked
   */
  Optional<BrowserSession> touch(
      String sessionId, Instant observedLastSeenOn, Instant now, Instant idleExpiresOn);

  /**
   * Rotates a due credential only when the observed live session has not changed.
   *
   * @return the updated session, or empty when the session changed, expired, or was revoked
   */
  Optional<BrowserSession> rotate(
      String sessionId,
      String observedTokenHash,
      Instant observedRotatedOn,
      String nextTokenHash,
      Instant now,
      Instant previousTokenExpiresOn,
      Instant idleExpiresOn);
}

package dev.christopherbell.configuration.security.browser;

import static dev.christopherbell.persistence.jooq.identity.Tables.BROWSER_SESSION;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL conditional transitions for authenticated browser-session activity. */
@PostgresPersistence
public final class PostgresBrowserSessionActivityStore implements BrowserSessionActivityStore {
  private final DSLContext database;

  public PostgresBrowserSessionActivityStore(DSLContext database) {
    this.database = database;
  }

  @Override
  public Optional<BrowserSession> touch(
      String sessionId, Instant observedLastSeenOn, Instant now, Instant idleExpiresOn) {
    return database.update(BROWSER_SESSION)
        .set(BROWSER_SESSION.LAST_SEEN_ON, now.atOffset(ZoneOffset.UTC))
        .set(BROWSER_SESSION.IDLE_EXPIRES_ON, idleExpiresOn.atOffset(ZoneOffset.UTC))
        .set(BROWSER_SESSION.VERSION, BROWSER_SESSION.VERSION.plus(1L))
        .where(live(sessionId, now, idleExpiresOn))
        .and(BROWSER_SESSION.LAST_SEEN_ON.eq(observedLastSeenOn.atOffset(ZoneOffset.UTC)))
        .returning()
        .fetchOptional(PostgresBrowserSessionMapper::map);
  }

  @Override
  public Optional<BrowserSession> rotate(
      String sessionId,
      String observedTokenHash,
      Instant observedRotatedOn,
      String nextTokenHash,
      Instant now,
      Instant previousTokenExpiresOn,
      Instant idleExpiresOn) {
    return database.update(BROWSER_SESSION)
        .set(BROWSER_SESSION.PREVIOUS_TOKEN_HASH, observedTokenHash)
        .set(BROWSER_SESSION.PREVIOUS_TOKEN_EXPIRES_ON,
            previousTokenExpiresOn.atOffset(ZoneOffset.UTC))
        .set(BROWSER_SESSION.TOKEN_HASH, nextTokenHash)
        .set(BROWSER_SESSION.ROTATED_ON, now.atOffset(ZoneOffset.UTC))
        .set(BROWSER_SESSION.LAST_SEEN_ON, now.atOffset(ZoneOffset.UTC))
        .set(BROWSER_SESSION.IDLE_EXPIRES_ON, idleExpiresOn.atOffset(ZoneOffset.UTC))
        .set(BROWSER_SESSION.VERSION, BROWSER_SESSION.VERSION.plus(1L))
        .where(live(sessionId, now, idleExpiresOn))
        .and(BROWSER_SESSION.TOKEN_HASH.eq(observedTokenHash))
        .and(BROWSER_SESSION.ROTATED_ON.isNotDistinctFrom(
            observedRotatedOn == null ? null : observedRotatedOn.atOffset(ZoneOffset.UTC)))
        .returning()
        .fetchOptional(PostgresBrowserSessionMapper::map);
  }

  private static org.jooq.Condition live(
      String sessionId, Instant now, Instant idleExpiresOn) {
    return BROWSER_SESSION.BROWSER_SESSION_ID.eq(sessionId)
        .and(BROWSER_SESSION.IDLE_EXPIRES_ON.gt(now.atOffset(ZoneOffset.UTC)))
        .and(BROWSER_SESSION.ABSOLUTE_EXPIRES_ON.ge(idleExpiresOn.atOffset(ZoneOffset.UTC)));
  }
}

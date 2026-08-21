package dev.christopherbell.configuration.security.browser;

import static dev.christopherbell.persistence.jooq.identity.Tables.BROWSER_SESSION;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Instant;
import java.time.ZoneOffset;
import org.jooq.DSLContext;

/** PostgreSQL persistence for revocable browser sessions. */
@PostgresPersistence
public class PostgresBrowserSessionRepository implements BrowserSessionRepository {
  private final DSLContext database;

  public PostgresBrowserSessionRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public BrowserSession save(BrowserSession session) {
    database.insertInto(BROWSER_SESSION)
        .set(BROWSER_SESSION.BROWSER_SESSION_ID, session.getId())
        .set(BROWSER_SESSION.ACCOUNT_ID, session.getAccountId())
        .set(BROWSER_SESSION.ROLE, session.getRole().name())
        .set(BROWSER_SESSION.TOKEN_HASH, session.getTokenHash())
        .set(BROWSER_SESSION.PREVIOUS_TOKEN_HASH, session.getPreviousTokenHash())
        .set(BROWSER_SESSION.PREVIOUS_TOKEN_EXPIRES_ON,
            timestamp(session.getPreviousTokenExpiresOn()))
        .set(BROWSER_SESSION.ACCOUNT_SECURITY_FINGERPRINT,
            session.getAccountSecurityFingerprint())
        .set(BROWSER_SESSION.CREATED_ON, timestamp(session.getCreatedOn()))
        .set(BROWSER_SESSION.LAST_SEEN_ON, timestamp(session.getLastSeenOn()))
        .set(BROWSER_SESSION.ROTATED_ON, timestamp(session.getRotatedOn()))
        .set(BROWSER_SESSION.IDLE_EXPIRES_ON, timestamp(session.getIdleExpiresOn()))
        .set(BROWSER_SESSION.ABSOLUTE_EXPIRES_ON, timestamp(session.getAbsoluteExpiresOn()))
        .onConflict(BROWSER_SESSION.BROWSER_SESSION_ID)
        .doUpdate()
        .set(BROWSER_SESSION.ACCOUNT_ID, session.getAccountId())
        .set(BROWSER_SESSION.ROLE, session.getRole().name())
        .set(BROWSER_SESSION.TOKEN_HASH, session.getTokenHash())
        .set(BROWSER_SESSION.PREVIOUS_TOKEN_HASH, session.getPreviousTokenHash())
        .set(BROWSER_SESSION.PREVIOUS_TOKEN_EXPIRES_ON,
            timestamp(session.getPreviousTokenExpiresOn()))
        .set(BROWSER_SESSION.ACCOUNT_SECURITY_FINGERPRINT,
            session.getAccountSecurityFingerprint())
        .set(BROWSER_SESSION.LAST_SEEN_ON, timestamp(session.getLastSeenOn()))
        .set(BROWSER_SESSION.ROTATED_ON, timestamp(session.getRotatedOn()))
        .set(BROWSER_SESSION.IDLE_EXPIRES_ON, timestamp(session.getIdleExpiresOn()))
        .set(BROWSER_SESSION.ABSOLUTE_EXPIRES_ON, timestamp(session.getAbsoluteExpiresOn()))
        .set(BROWSER_SESSION.VERSION, BROWSER_SESSION.VERSION.plus(1L))
        .execute();
    return database.selectFrom(BROWSER_SESSION)
        .where(BROWSER_SESSION.BROWSER_SESSION_ID.eq(session.getId()))
        .fetchOne(PostgresBrowserSessionMapper::map);
  }

  @Override
  public void delete(BrowserSession session) {
    deleteById(session.getId());
  }

  @Override
  public void deleteById(String id) {
    database.deleteFrom(BROWSER_SESSION)
        .where(BROWSER_SESSION.BROWSER_SESSION_ID.eq(id))
        .execute();
  }

  @Override
  public long deleteByAccountId(String accountId) {
    return database.deleteFrom(BROWSER_SESSION)
        .where(BROWSER_SESSION.ACCOUNT_ID.eq(accountId))
        .execute();
  }

  private static java.time.OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
}

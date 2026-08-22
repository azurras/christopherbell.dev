package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL conditional transitions for authenticated browser-session activity. */
@PostgresPersistence
public class PostgresBrowserSessionActivityStore implements BrowserSessionActivityStore {
  private final JdbcClient database;
  private final String table;

  public PostgresBrowserSessionActivityStore(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("identity", "browser_session");
  }

  @Override
  public Optional<BrowserSession> touch(
      String sessionId, Instant observedLastSeenOn, Instant now, Instant idleExpiresOn) {
    return database.sql("""
            update %s set
              last_seen_on = :now,
              idle_expires_on = :idleExpiresOn,
              version = version + 1
            where browser_session_id = :sessionId
              and idle_expires_on > :now
              and absolute_expires_on >= :idleExpiresOn
              and last_seen_on = :observedLastSeenOn
            returning *
            """.formatted(table))
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("idleExpiresOn", idleExpiresOn.atOffset(ZoneOffset.UTC))
        .param("sessionId", sessionId)
        .param("observedLastSeenOn", observedLastSeenOn.atOffset(ZoneOffset.UTC))
        .query(PostgresBrowserSessionMapper::map)
        .optional();
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
    return database.sql("""
            update %s set
              previous_token_hash = :observedTokenHash,
              previous_token_expires_on = :previousTokenExpiresOn,
              token_hash = :nextTokenHash,
              rotated_on = :now,
              last_seen_on = :now,
              idle_expires_on = :idleExpiresOn,
              version = version + 1
            where browser_session_id = :sessionId
              and idle_expires_on > :now
              and absolute_expires_on >= :idleExpiresOn
              and token_hash = :observedTokenHash
              and rotated_on is not distinct from :observedRotatedOn
            returning *
            """.formatted(table))
        .param("observedTokenHash", observedTokenHash)
        .param("previousTokenExpiresOn", previousTokenExpiresOn.atOffset(ZoneOffset.UTC))
        .param("nextTokenHash", nextTokenHash)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("idleExpiresOn", idleExpiresOn.atOffset(ZoneOffset.UTC))
        .param("sessionId", sessionId)
        .param("observedRotatedOn",
            observedRotatedOn == null ? null : observedRotatedOn.atOffset(ZoneOffset.UTC),
            java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .query(PostgresBrowserSessionMapper::map)
        .optional();
  }
}

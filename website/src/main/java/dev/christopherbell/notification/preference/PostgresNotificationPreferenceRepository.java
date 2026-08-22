package dev.christopherbell.notification.preference;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL persistence for account notification preferences. */
@PostgresPersistence
public class PostgresNotificationPreferenceRepository
    implements NotificationPreferenceRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresNotificationPreferenceRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("communication", "notification_preference");
  }

  @Override
  public NotificationPreference save(NotificationPreference preference) {
    return database.sql("""
            insert into %s
              (notification_preference_id, account_id, mentions, likes, comments, messages,
               wfl_sessions, created_on, last_updated_on)
            values
              (:id, :accountId, :mentions, :likes, :comments, :messages,
               :wflSessions, :createdOn, :lastUpdatedOn)
            on conflict (account_id) do update set
              mentions = excluded.mentions,
              likes = excluded.likes,
              comments = excluded.comments,
              messages = excluded.messages,
              wfl_sessions = excluded.wfl_sessions,
              last_updated_on = excluded.last_updated_on,
              version = %s.version + 1
            returning *
            """.formatted(table, table))
        .param("id", preference.getId())
        .param("accountId", preference.getAccountId())
        .param("mentions", preference.isMentions())
        .param("likes", preference.isLikes())
        .param("comments", preference.isComments())
        .param("messages", preference.isMessages())
        .param("wflSessions", preference.isWflSessions())
        .param("createdOn", timestamp(preference.getCreatedOn()))
        .param("lastUpdatedOn", timestamp(preference.getLastUpdatedOn()))
        .query(PostgresNotificationPreferenceRepository::map)
        .single();
  }

  @Override
  public Optional<NotificationPreference> findByAccountId(String accountId) {
    return database.sql("select * from %s where account_id = :accountId".formatted(table))
        .param("accountId", accountId)
        .query(PostgresNotificationPreferenceRepository::map)
        .optional();
  }

  private static NotificationPreference map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    return NotificationPreference.builder()
        .id(row.getString("notification_preference_id"))
        .accountId(row.getString("account_id"))
        .mentions(row.getBoolean("mentions"))
        .likes(row.getBoolean("likes"))
        .comments(row.getBoolean("comments"))
        .messages(row.getBoolean("messages"))
        .wflSessions(row.getBoolean("wfl_sessions"))
        .createdOn(instant(row.getObject("created_on", OffsetDateTime.class)))
        .lastUpdatedOn(instant(row.getObject("last_updated_on", OffsetDateTime.class)))
        .build();
  }

  private static OffsetDateTime timestamp(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

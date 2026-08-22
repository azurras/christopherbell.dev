package dev.christopherbell.notification;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of the notification persistence port. */
@PostgresPersistence
public class PostgresNotificationRepository implements NotificationRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresNotificationRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("communication", "notification");
  }

  @Override
  public Notification save(Notification notification) {
    database.sql("""
            insert into %s (
              notification_id, account_id, actor_account_id, actor_username, post_id,
              post_text, message_id, message_text, lunch_session_id, lunch_session_text,
              notification_type, is_read, created_on)
            values (
              :id, :accountId, :actorAccountId, :actorUsername, :postId,
              :postText, :messageId, :messageText, :lunchSessionId, :lunchSessionText,
              :type, :read, :createdOn)
            on conflict (notification_id) do update set
              account_id = excluded.account_id,
              actor_account_id = excluded.actor_account_id,
              actor_username = excluded.actor_username,
              post_id = excluded.post_id,
              post_text = excluded.post_text,
              message_id = excluded.message_id,
              message_text = excluded.message_text,
              lunch_session_id = excluded.lunch_session_id,
              lunch_session_text = excluded.lunch_session_text,
              notification_type = excluded.notification_type,
              is_read = excluded.is_read,
              version = %s.version + 1
            """.formatted(table, table))
        .paramSource(new MapSqlParameterSource()
            .addValue("id", notification.getId())
            .addValue("accountId", notification.getAccountId())
            .addValue("actorAccountId", notification.getActorAccountId(), Types.VARCHAR)
            .addValue("actorUsername", notification.getActorUsername(), Types.VARCHAR)
            .addValue("postId", notification.getPostId(), Types.VARCHAR)
            .addValue("postText", notification.getPostText(), Types.VARCHAR)
            .addValue("messageId", notification.getMessageId(), Types.VARCHAR)
            .addValue("messageText", notification.getMessageText(), Types.VARCHAR)
            .addValue("lunchSessionId", notification.getWhatsForLunchSessionId(), Types.VARCHAR)
            .addValue("lunchSessionText", notification.getWhatsForLunchSessionText(), Types.VARCHAR)
            .addValue("type", notification.getNotificationType().name())
            .addValue("read", Boolean.TRUE.equals(notification.getRead()))
            .addValue("createdOn", notification.getCreatedOn().atOffset(ZoneOffset.UTC)))
        .update();
    return findById(notification.getId()).orElseThrow();
  }

  @Override
  public Optional<Notification> findById(String id) {
    return database.sql("select * from %s where notification_id = :id".formatted(table))
        .param("id", id).query(PostgresNotificationRepository::map).optional();
  }

  @Override
  public List<Notification> findByAccountIdOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    var suffix = pageable.isPaged() ? "limit :limit offset :offset" : "";
    var statement = database.sql("""
            select * from %s where account_id = :accountId
            order by created_on desc, notification_id desc %s
            """.formatted(table, suffix)).param("accountId", accountId);
    if (pageable.isPaged()) {
      statement.param("limit", pageable.getPageSize())
          .param("offset", Math.toIntExact(pageable.getOffset()));
    }
    return statement.query(PostgresNotificationRepository::map).list();
  }

  @Override
  public long countByAccountIdAndReadFalse(String accountId) {
    return database.sql("select count(*) from %s where account_id = :accountId and not is_read"
            .formatted(table))
        .param("accountId", accountId).query(Long.class).single();
  }

  public static Notification map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    return Notification.builder()
        .id(row.getString("notification_id"))
        .accountId(row.getString("account_id"))
        .actorAccountId(row.getString("actor_account_id"))
        .actorUsername(row.getString("actor_username"))
        .postId(row.getString("post_id"))
        .postText(row.getString("post_text"))
        .messageId(row.getString("message_id"))
        .messageText(row.getString("message_text"))
        .whatsForLunchSessionId(row.getString("lunch_session_id"))
        .whatsForLunchSessionText(row.getString("lunch_session_text"))
        .notificationType(NotificationType.valueOf(row.getString("notification_type")))
        .read(row.getBoolean("is_read"))
        .createdOn(row.getObject("created_on", OffsetDateTime.class).toInstant())
        .build();
  }
}

package dev.christopherbell.notification.inbox;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.PostgresNotificationRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL stable owner-scoped notification reads and bulk read-state updates. */
@PostgresPersistence
public class PostgresNotificationQueryRepository implements NotificationQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final JdbcClient database;
  private final StableCursorCodec cursors;
  private final String table;

  public PostgresNotificationQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
    table = schemas.qualifiedTable("communication", "notification");
  }

  @Override
  public NotificationPage page(
      String accountId, Optional<StableCursor> cursor, int requestedSize) {
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var boundary = cursor.orElse(null);
    var cursorClause = boundary == null ? "" : """
        and (created_on < :cursorTime
          or (created_on = :cursorTime and notification_id < :cursorId))
        """;
    var statement = database.sql("""
            select * from %s where account_id = :accountId %s
            order by created_on desc, notification_id desc limit :limit
            """.formatted(table, cursorClause))
        .param("accountId", accountId).param("limit", size + 1);
    if (boundary != null) {
      statement.param("cursorTime", boundary.timestamp().atOffset(ZoneOffset.UTC))
          .param("cursorId", boundary.id());
    }
    var loaded = statement.query(PostgresNotificationRepository::map).list();
    var hasNext = loaded.size() > size;
    var notifications = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !notifications.isEmpty()) {
      var last = notifications.getLast();
      nextCursor = cursors.encode(new StableCursor(last.getCreatedOn(), last.getId()));
    }
    return new NotificationPage(
        notifications.stream().map(this::toDetail).toList(), nextCursor);
  }

  @Override
  public NotificationReadResult markAllRead(String accountId) {
    var updated = database.sql("""
            update %s set is_read = true, version = version + 1
            where account_id = :accountId and not is_read
            """.formatted(table))
        .param("accountId", accountId).update();
    return new NotificationReadResult(updated);
  }

  private NotificationDetail toDetail(Notification notification) {
    return NotificationDetail.builder()
        .id(notification.getId()).accountId(notification.getAccountId())
        .actorAccountId(notification.getActorAccountId())
        .actorUsername(notification.getActorUsername()).postId(notification.getPostId())
        .postText(notification.getPostText()).messageId(notification.getMessageId())
        .messageText(notification.getMessageText())
        .whatsForLunchSessionId(notification.getWhatsForLunchSessionId())
        .whatsForLunchSessionText(notification.getWhatsForLunchSessionText())
        .notificationType(notification.getNotificationType())
        .read(Boolean.TRUE.equals(notification.getRead()))
        .createdOn(notification.getCreatedOn()).build();
  }
}

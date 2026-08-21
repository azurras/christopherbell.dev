package dev.christopherbell.notification.inbox;

import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.PostgresNotificationRepository;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL stable owner-scoped notification reads and bulk read-state updates. */
@PostgresPersistence
public class PostgresNotificationQueryRepository implements NotificationQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final DSLContext database;
  private final StableCursorCodec cursors;

  public PostgresNotificationQueryRepository(DSLContext database, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
  }

  @Override
  public NotificationPage page(
      String accountId, Optional<StableCursor> cursor, int requestedSize) {
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    Condition condition = NOTIFICATION.ACCOUNT_ID.eq(accountId);
    if (cursor.isPresent()) {
      var boundary = cursor.orElseThrow();
      var timestamp = boundary.timestamp().atOffset(ZoneOffset.UTC);
      condition = condition.and(NOTIFICATION.CREATED_ON.lt(timestamp)
          .or(NOTIFICATION.CREATED_ON.eq(timestamp)
              .and(NOTIFICATION.NOTIFICATION_ID.lt(boundary.id()))));
    }
    var loaded = database.selectFrom(NOTIFICATION)
        .where(condition)
        .orderBy(NOTIFICATION.CREATED_ON.desc(), NOTIFICATION.NOTIFICATION_ID.desc())
        .limit(size + 1)
        .fetch(PostgresNotificationRepository::map);
    var hasNext = loaded.size() > size;
    var notifications = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !notifications.isEmpty()) {
      var boundary = notifications.getLast();
      nextCursor = cursors.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new NotificationPage(notifications.stream().map(this::toDetail).toList(), nextCursor);
  }

  @Override
  public NotificationReadResult markAllRead(String accountId) {
    var updated = database.update(NOTIFICATION)
        .set(NOTIFICATION.IS_READ, true)
        .set(NOTIFICATION.VERSION, NOTIFICATION.VERSION.plus(1L))
        .where(NOTIFICATION.ACCOUNT_ID.eq(accountId).and(NOTIFICATION.IS_READ.isFalse()))
        .execute();
    return new NotificationReadResult(updated);
  }

  private NotificationDetail toDetail(Notification notification) {
    return NotificationDetail.builder()
        .id(notification.getId())
        .accountId(notification.getAccountId())
        .actorAccountId(notification.getActorAccountId())
        .actorUsername(notification.getActorUsername())
        .postId(notification.getPostId())
        .postText(notification.getPostText())
        .messageId(notification.getMessageId())
        .messageText(notification.getMessageText())
        .whatsForLunchSessionId(notification.getWhatsForLunchSessionId())
        .whatsForLunchSessionText(notification.getWhatsForLunchSessionText())
        .notificationType(notification.getNotificationType())
        .read(Boolean.TRUE.equals(notification.getRead()))
        .createdOn(notification.getCreatedOn())
        .build();
  }
}

package dev.christopherbell.notification;

import static dev.christopherbell.persistence.jooq.communication.Tables.NOTIFICATION;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationType;
import dev.christopherbell.persistence.jooq.communication.tables.records.NotificationRecord;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.data.domain.Pageable;

/** PostgreSQL implementation of the notification persistence port. */
@PostgresPersistence
public class PostgresNotificationRepository implements NotificationRepository {
  private final DSLContext database;

  public PostgresNotificationRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public Notification save(Notification notification) {
    database.insertInto(NOTIFICATION)
        .set(NOTIFICATION.NOTIFICATION_ID, notification.getId())
        .set(NOTIFICATION.ACCOUNT_ID, notification.getAccountId())
        .set(NOTIFICATION.ACTOR_ACCOUNT_ID, notification.getActorAccountId())
        .set(NOTIFICATION.ACTOR_USERNAME, notification.getActorUsername())
        .set(NOTIFICATION.POST_ID, notification.getPostId())
        .set(NOTIFICATION.POST_TEXT, notification.getPostText())
        .set(NOTIFICATION.MESSAGE_ID, notification.getMessageId())
        .set(NOTIFICATION.MESSAGE_TEXT, notification.getMessageText())
        .set(NOTIFICATION.LUNCH_SESSION_ID, notification.getWhatsForLunchSessionId())
        .set(NOTIFICATION.LUNCH_SESSION_TEXT, notification.getWhatsForLunchSessionText())
        .set(NOTIFICATION.NOTIFICATION_TYPE, notification.getNotificationType().name())
        .set(NOTIFICATION.IS_READ, Boolean.TRUE.equals(notification.getRead()))
        .set(NOTIFICATION.CREATED_ON, notification.getCreatedOn().atOffset(ZoneOffset.UTC))
        .onConflict(NOTIFICATION.NOTIFICATION_ID)
        .doUpdate()
        .set(NOTIFICATION.ACCOUNT_ID, notification.getAccountId())
        .set(NOTIFICATION.ACTOR_ACCOUNT_ID, notification.getActorAccountId())
        .set(NOTIFICATION.ACTOR_USERNAME, notification.getActorUsername())
        .set(NOTIFICATION.POST_ID, notification.getPostId())
        .set(NOTIFICATION.POST_TEXT, notification.getPostText())
        .set(NOTIFICATION.MESSAGE_ID, notification.getMessageId())
        .set(NOTIFICATION.MESSAGE_TEXT, notification.getMessageText())
        .set(NOTIFICATION.LUNCH_SESSION_ID, notification.getWhatsForLunchSessionId())
        .set(NOTIFICATION.LUNCH_SESSION_TEXT, notification.getWhatsForLunchSessionText())
        .set(NOTIFICATION.NOTIFICATION_TYPE, notification.getNotificationType().name())
        .set(NOTIFICATION.IS_READ, Boolean.TRUE.equals(notification.getRead()))
        .set(NOTIFICATION.VERSION, NOTIFICATION.VERSION.plus(1L))
        .execute();
    return findById(notification.getId()).orElseThrow();
  }

  @Override
  public Optional<Notification> findById(String id) {
    return database.selectFrom(NOTIFICATION).where(NOTIFICATION.NOTIFICATION_ID.eq(id))
        .fetchOptional(PostgresNotificationRepository::map);
  }

  @Override
  public List<Notification> findByAccountIdOrderByCreatedOnDesc(
      String accountId, Pageable pageable) {
    var query = database.selectFrom(NOTIFICATION)
        .where(NOTIFICATION.ACCOUNT_ID.eq(accountId))
        .orderBy(NOTIFICATION.CREATED_ON.desc(), NOTIFICATION.NOTIFICATION_ID.desc());
    return pageable.isPaged()
        ? query.limit(pageable.getPageSize()).offset(Math.toIntExact(pageable.getOffset()))
            .fetch(PostgresNotificationRepository::map)
        : query.fetch(PostgresNotificationRepository::map);
  }

  @Override
  public long countByAccountIdAndReadFalse(String accountId) {
    return database.fetchCount(NOTIFICATION,
        NOTIFICATION.ACCOUNT_ID.eq(accountId).and(NOTIFICATION.IS_READ.isFalse()));
  }

  public static Notification map(NotificationRecord record) {
    return Notification.builder()
        .id(record.getNotificationId())
        .accountId(record.getAccountId())
        .actorAccountId(record.getActorAccountId())
        .actorUsername(record.getActorUsername())
        .postId(record.getPostId())
        .postText(record.getPostText())
        .messageId(record.getMessageId())
        .messageText(record.getMessageText())
        .whatsForLunchSessionId(record.getLunchSessionId())
        .whatsForLunchSessionText(record.getLunchSessionText())
        .notificationType(NotificationType.valueOf(record.getNotificationType()))
        .read(record.getIsRead())
        .createdOn(record.getCreatedOn().toInstant())
        .build();
  }
}

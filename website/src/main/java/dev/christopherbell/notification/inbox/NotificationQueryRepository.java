package dev.christopherbell.notification.inbox;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Stable owner-scoped reads and bulk updates for the notification inbox. */
@MongoPersistence
@Repository
public class NotificationQueryRepository implements NotificationQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final KindScopedMongoOperations<Notification> mongo;
  private final StableCursorCodec cursorCodec;

  public NotificationQueryRepository(
      DomainMongoOperationsFactory factory, StableCursorCodec cursorCodec) {
    this.mongo = factory.forType(Notification.class);
    this.cursorCodec = cursorCodec;
  }

  /** Reads one stable newest-first page for one recipient. */
  public NotificationPage page(
      String accountId,
      Optional<StableCursor> cursor,
      int requestedSize
  ) {
    int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var criteria = Criteria.where("accountId").is(accountId);
    if (cursor.isPresent()) {
      var boundary = cursor.get();
      var before = new Criteria().orOperator(
          Criteria.where("createdOn").lt(boundary.timestamp()),
          new Criteria().andOperator(
              Criteria.where("createdOn").is(boundary.timestamp()),
              Criteria.where("id").lt(boundary.id())));
      criteria = new Criteria().andOperator(criteria, before);
    }
    var query = new Query(criteria)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .limit(size + 1);
    var loaded = mongo.find(query, org.springframework.data.domain.Pageable.unpaged());
    boolean hasNext = loaded.size() > size;
    var notifications = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !notifications.isEmpty()) {
      var boundary = notifications.get(notifications.size() - 1);
      nextCursor = cursorCodec.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new NotificationPage(notifications.stream().map(this::toDetail).toList(), nextCursor);
  }

  /** Atomically marks every unread notification for one recipient as read. */
  public NotificationReadResult markAllRead(String accountId) {
    var query = Query.query(new Criteria().andOperator(
        Criteria.where("accountId").is(accountId),
        Criteria.where("read").ne(true)));
    var result = mongo.updateMulti(query, Update.update("read", true));
    return new NotificationReadResult(result.getModifiedCount());
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

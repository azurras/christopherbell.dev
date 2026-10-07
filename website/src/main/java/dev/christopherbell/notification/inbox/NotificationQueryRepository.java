package dev.christopherbell.notification.inbox;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.notification.model.Notification;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

  /** Reads one stable newest-first page for one recipient, after the cursor when present. */
  @Override
  public NotificationPage page(String accountId, Optional<StableCursor> olderThan, int requestedSize) {
    int pageSize = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    Criteria forRecipient = Criteria.where("accountId").is(accountId);
    Criteria matchingNotifications = olderThan
        .map(cursor -> new Criteria().andOperator(forRecipient, olderThanCursor(cursor)))
        .orElse(forRecipient);
    Query oneExtraNotification = new Query(matchingNotifications)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .limit(pageSize + 1);
    List<Notification> loadedNotifications = mongo.find(oneExtraNotification, Pageable.unpaged());
    boolean hasOlderNotifications = loadedNotifications.size() > pageSize;
    List<Notification> pageNotifications = loadedNotifications.stream().limit(pageSize).toList();
    String nextCursor = null;
    if (hasOlderNotifications && !pageNotifications.isEmpty()) {
      Notification oldestOnPage = pageNotifications.getLast();
      nextCursor = cursorCodec.encode(new StableCursor(oldestOnPage.getCreatedOn(), oldestOnPage.getId()));
    }
    return new NotificationPage(
        pageNotifications.stream().map(NotificationDetail::from).toList(), nextCursor);
  }

  /** Atomically marks every unread notification for one recipient as read. */
  @Override
  public NotificationReadResult markAllRead(String accountId) {
    Query unreadForRecipient = Query.query(new Criteria().andOperator(
        Criteria.where("accountId").is(accountId),
        Criteria.where("read").ne(true)));
    long markedCount = mongo.updateMulti(unreadForRecipient, Update.update("read", true)).getModifiedCount();
    return new NotificationReadResult(markedCount);
  }

  private static Criteria olderThanCursor(StableCursor cursor) {
    return new Criteria().orOperator(
        Criteria.where("createdOn").lt(cursor.timestamp()),
        new Criteria().andOperator(
            Criteria.where("createdOn").is(cursor.timestamp()),
            Criteria.where("id").lt(cursor.id())));
  }
}

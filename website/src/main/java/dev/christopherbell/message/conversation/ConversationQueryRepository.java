package dev.christopherbell.message.conversation;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Mongo queries for distinct conversation summaries and stable history pages. */
@Repository
public class ConversationQueryRepository {
  private static final int MAX_PAGE_SIZE = 100;
  private final KindScopedMongoOperations<Message> messages;
  private final StableCursorCodec cursorCodec;

  public ConversationQueryRepository(
      DomainMongoOperationsFactory factory, StableCursorCodec cursorCodec) {
    this.messages = factory.forType(Message.class);
    this.cursorCodec = cursorCodec;
  }

  /** Returns the newest message from each distinct conversation that is visible to the owner. */
  public List<Message> latestDistinctVisible(String ownerAccountId, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 50));
    AggregationOperation archiveLookup = context -> new Document("$lookup", new Document()
        .append("from", "sessions")
        .append("let", new Document("conversationKey", "$conversationKey"))
        .append("pipeline", List.of(new Document("$match", new Document()
            .append("_kind", "conversation_archive_state")
            .append("payload.ownerAccountId", ownerAccountId)
            .append("$expr", new Document("$eq", List.of(
                "$payload.conversationKey", "$$conversationKey"))))))
        .append("as", "ownerArchive"));
    AggregationOperation visibleAfterArchive = context -> new Document("$match",
        new Document("$expr", new Document("$or", List.of(
            new Document("$eq", List.of(
                new Document("$size", "$ownerArchive"), 0)),
            new Document("$ne", List.of(
                "$_id",
                new Document("$arrayElemAt", List.of(
                    "$ownerArchive.payload.archivedThroughMessageId", 0))))))));
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("participantIds").is(ownerAccountId)),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "createdOn", "_id")),
        Aggregation.group("conversationKey").first(Aggregation.ROOT).as("latest"),
        Aggregation.replaceRoot("latest"),
        archiveLookup,
        visibleAfterArchive,
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "createdOn", "_id")),
        Aggregation.limit(limit));
    return messages.aggregate(aggregation, Message.class);
  }

  /** Counts unread incoming messages for all returned conversation peers in one query. */
  public Map<String, Long> unreadCounts(
      String recipientAccountId,
      Collection<String> senderAccountIds
  ) {
    if (senderAccountIds.isEmpty()) {
      return Map.of();
    }
    var criteria = new Criteria().andOperator(
        Criteria.where("recipientAccountId").is(recipientAccountId),
        Criteria.where("senderAccountId").in(senderAccountIds),
        Criteria.where("read").is(false));
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(criteria),
        Aggregation.group("senderAccountId").count().as("count"));
    return messages.aggregate(aggregation, ConversationUnreadCount.class).stream()
        .collect(Collectors.toUnmodifiableMap(
            ConversationUnreadCount::id,
            ConversationUnreadCount::count));
  }

  /** Reads one newest-to-oldest stable slice; callers choose the presentation order. */
  public ConversationMessageSlice page(
      String conversationKey,
      Optional<StableCursor> cursor,
      int requestedSize
  ) {
    int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var criteria = Criteria.where("conversationKey").is(conversationKey);
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
    var loaded = messages.find(query, org.springframework.data.domain.Pageable.unpaged());
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var boundary = items.get(items.size() - 1);
      nextCursor = cursorCodec.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new ConversationMessageSlice(items, nextCursor);
  }
}

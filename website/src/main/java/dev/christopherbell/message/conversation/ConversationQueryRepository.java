package dev.christopherbell.message.conversation;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.message.model.Message;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Mongo queries for distinct conversation summaries and stable history pages. */
@MongoPersistence
@Repository
public class ConversationQueryRepository implements ConversationQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_CONVERSATIONS = 50;
  private final KindScopedMongoOperations<Message> messages;
  private final StableCursorCodec cursorCodec;

  public ConversationQueryRepository(
      DomainMongoOperationsFactory factory, StableCursorCodec cursorCodec) {
    this.messages = factory.forType(Message.class);
    this.cursorCodec = cursorCodec;
  }

  /** Returns the newest message from each distinct conversation that is visible to the owner. */
  @Override
  public List<Message> latestDistinctVisible(String ownerAccountId, int requestedLimit) {
    int conversationLimit = Math.max(1, Math.min(requestedLimit, MAX_CONVERSATIONS));
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
    Aggregation latestVisiblePerConversation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("participantIds").is(ownerAccountId)),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "createdOn", "_id")),
        Aggregation.group("conversationKey").first(Aggregation.ROOT).as("latest"),
        Aggregation.replaceRoot("latest"),
        archiveLookup,
        visibleAfterArchive,
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "createdOn", "_id")),
        Aggregation.limit(conversationLimit));
    return messages.aggregate(
        KindScopedAggregation.withForeignKinds(
            latestVisiblePerConversation, KindScopedAggregation.ForeignKind.CONVERSATION_ARCHIVE_STATE),
        Message.class);
  }

  /** Counts unread incoming messages for all returned conversation peers in one query. */
  @Override
  public Map<String, Long> unreadCounts(
      String recipientAccountId,
      Collection<String> senderAccountIds
  ) {
    if (senderAccountIds.isEmpty()) {
      return Map.of();
    }
    Criteria unreadFromSenders = new Criteria().andOperator(
        Criteria.where("recipientAccountId").is(recipientAccountId),
        Criteria.where("senderAccountId").in(senderAccountIds),
        Criteria.where("read").is(false));
    Aggregation countBySender = Aggregation.newAggregation(
        Aggregation.match(unreadFromSenders),
        Aggregation.group("senderAccountId").count().as("count"));
    return messages.aggregate(KindScopedAggregation.local(countBySender), ConversationUnreadCount.class).stream()
        .collect(Collectors.toUnmodifiableMap(
            ConversationUnreadCount::id,
            ConversationUnreadCount::count));
  }

  /** Reads one newest-to-oldest stable slice; callers choose the presentation order. */
  @Override
  public ConversationMessageSlice page(
      String conversationKey, Optional<StableCursor> olderThan, int requestedSize) {
    Criteria inConversation = Criteria.where("conversationKey").is(conversationKey);
    Criteria matchingMessages = olderThan
        .map(cursor -> new Criteria().andOperator(inConversation, olderThanCursor(cursor)))
        .orElse(inConversation);
    return newestFirstSlice(matchingMessages, requestedSize);
  }

  private static Criteria olderThanCursor(StableCursor cursor) {
    return new Criteria().orOperator(
        Criteria.where("createdOn").lt(cursor.timestamp()),
        new Criteria().andOperator(
            Criteria.where("createdOn").is(cursor.timestamp()),
            Criteria.where("id").lt(cursor.id())));
  }

  private ConversationMessageSlice newestFirstSlice(Criteria matchingMessages, int requestedSize) {
    int pageSize = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    Query oneExtraMessage = new Query(matchingMessages)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .limit(pageSize + 1);
    List<Message> loadedMessages = messages.find(oneExtraMessage, Pageable.unpaged());
    boolean hasOlderMessages = loadedMessages.size() > pageSize;
    List<Message> pageMessages = loadedMessages.stream().limit(pageSize).toList();
    String nextCursor = null;
    if (hasOlderMessages && !pageMessages.isEmpty()) {
      Message oldestOnPage = pageMessages.getLast();
      nextCursor = cursorCodec.encode(new StableCursor(oldestOnPage.getCreatedOn(), oldestOnPage.getId()));
    }
    return new ConversationMessageSlice(pageMessages, nextCursor);
  }
}

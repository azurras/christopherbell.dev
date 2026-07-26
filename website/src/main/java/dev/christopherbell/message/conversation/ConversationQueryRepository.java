package dev.christopherbell.message.conversation;

import dev.christopherbell.message.model.Message;
import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Mongo queries for distinct conversation summaries and stable history pages. */
@Repository
@RequiredArgsConstructor
public class ConversationQueryRepository {
  private static final int MAX_PAGE_SIZE = 100;
  private final MongoTemplate mongo;
  private final StableCursorCodec cursorCodec;

  /** Returns the newest message from each distinct conversation that is visible to the owner. */
  public List<Message> latestDistinctVisible(String ownerAccountId, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 50));
    AggregationOperation archiveLookup = context -> new Document("$lookup", new Document()
        .append("from", "conversation_archive_states")
        .append("let", new Document("conversationKey", "$conversationKey"))
        .append("pipeline", List.of(new Document("$match", new Document()
            .append("ownerAccountId", ownerAccountId)
            .append("$expr", new Document("$eq", List.of(
                "$conversationKey", "$$conversationKey"))))))
        .append("as", "ownerArchive"));
    AggregationOperation visibleAfterArchive = context -> new Document("$match",
        new Document("$expr", new Document("$or", List.of(
            new Document("$eq", List.of(
                new Document("$size", "$ownerArchive"), 0)),
            new Document("$ne", List.of(
                "$_id",
                new Document("$arrayElemAt", List.of(
                    "$ownerArchive.archivedThroughMessageId", 0))))))));
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("participantIds").is(ownerAccountId)),
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "createdOn", "_id")),
        Aggregation.group("conversationKey").first(Aggregation.ROOT).as("latest"),
        Aggregation.replaceRoot("latest"),
        archiveLookup,
        visibleAfterArchive,
        Aggregation.sort(Sort.by(Sort.Direction.DESC, "createdOn", "_id")),
        Aggregation.limit(limit));
    return mongo.aggregate(aggregation, "messages", Message.class).getMappedResults();
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
              Criteria.where("_id").lt(boundary.id())));
      criteria = new Criteria().andOperator(criteria, before);
    }
    var query = new Query(criteria)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "_id"))
        .limit(size + 1);
    var loaded = mongo.find(query, Message.class);
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

package dev.christopherbell.post.discovery;

import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Bounded MongoDB queries for anonymous Void discovery. */
@Repository
@RequiredArgsConstructor
public final class VoidDiscoveryQueryRepository {
  private static final int MAX_PAGE_SIZE = 24;
  private static final String POSTS_COLLECTION = "posts";

  private final MongoTemplate mongo;
  private final StableCursorCodec cursors;

  public VoidDiscoveryPage<Post> newArrivals(
      Optional<StableCursor> cursor, int requestedSize, Instant now) {
    return rootPage("createdOn", Sort.Direction.DESC, cursor, requestedSize, now, false);
  }

  public VoidDiscoveryPage<Post> fadingSoon(
      Optional<StableCursor> cursor, int requestedSize, Instant now) {
    return rootPage("expiresOn", Sort.Direction.ASC, cursor, requestedSize, now, false);
  }

  public VoidDiscoveryPage<Post> recentlyRevived(
      Optional<StableCursor> cursor, int requestedSize, Instant now) {
    return rootPage("lastExtendedOn", Sort.Direction.DESC, cursor, requestedSize, now, true);
  }

  public VoidDiscoveryPage<Post> topic(
      String canonical,
      Optional<StableCursor> cursor,
      int requestedSize,
      Instant now
  ) {
    int size = pageSize(requestedSize);
    var operations = new ArrayList<AggregationOperation>();
    operations.add(raw("$match", new Document("expiresOn", new Document("$gt", Date.from(now)))
        .append("topics.canonical", canonical)));
    operations.add(raw("$group", new Document("_id", "$rootId")));
    operations.add(raw("$lookup", new Document("from", POSTS_COLLECTION)
        .append("localField", "_id")
        .append("foreignField", "_id")
        .append("as", "root")));
    operations.add(raw("$unwind", "$root"));
    operations.add(raw("$replaceRoot", new Document("newRoot", "$root")));
    operations.add(raw("$match", rootMatchWithCursor("createdOn", Sort.Direction.DESC, cursor, now)));
    operations.add(raw("$sort", new Document("createdOn", -1).append("_id", -1)));
    operations.add(raw("$limit", size + 1));

    var aggregation = Aggregation.newAggregation(operations);
    var loaded = mongo.aggregate(aggregation, POSTS_COLLECTION, Post.class).getMappedResults();
    return postPage(loaded, size, Post::getCreatedOn);
  }

  public VoidDiscoveryPage<VoidTopicSummary> topics(
      Optional<StableCursor> cursor, int requestedSize, Instant now) {
    int size = pageSize(requestedSize);
    var operations = new ArrayList<AggregationOperation>();
    operations.add(raw("$match", new Document("expiresOn", new Document("$gt", Date.from(now)))
        .append("topics.0", new Document("$exists", true))));
    operations.add(raw("$unwind", "$topics"));
    operations.add(raw("$lookup", new Document("from", POSTS_COLLECTION)
        .append("localField", "rootId")
        .append("foreignField", "_id")
        .append("as", "root")));
    operations.add(raw("$unwind", "$root"));
    operations.add(raw("$match", new Document("root.parentId", null)
        .append("root.expiresOn", new Document("$gt", Date.from(now)))));
    operations.add(raw("$project", new Document("canonical", "$topics.canonical")
        .append("display", "$topics.display")
        .append("activityOn", new Document("$ifNull", List.of("$lastExtendedOn", "$createdOn")))));
    operations.add(raw("$group", new Document("_id", "$canonical")
        .append("display", new Document("$first", "$display"))
        .append("activityOn", new Document("$max", "$activityOn"))));
    cursor.ifPresent(boundary -> operations.add(raw("$match", descendingAscendingBoundary(
        "activityOn", "_id", boundary))));
    operations.add(raw("$sort", new Document("activityOn", -1).append("_id", 1)));
    operations.add(raw("$limit", size + 1));
    operations.add(raw("$project", new Document("_id", 0)
        .append("canonical", "$_id")
        .append("display", 1)
        .append("activityOn", 1)));

    var aggregation = Aggregation.newAggregation(operations);
    var loaded = mongo.aggregate(aggregation, POSTS_COLLECTION, VoidTopicSummary.class)
        .getMappedResults();
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var boundary = items.get(items.size() - 1);
      nextCursor = cursors.encode(new StableCursor(boundary.activityOn(), boundary.canonical()));
    }
    return new VoidDiscoveryPage<>(items, nextCursor);
  }

  private VoidDiscoveryPage<Post> rootPage(
      String timestampField,
      Sort.Direction direction,
      Optional<StableCursor> cursor,
      int requestedSize,
      Instant now,
      boolean requireRevival
  ) {
    int size = pageSize(requestedSize);
    var criteria = rootCriteria(now, requireRevival);
    if (cursor.isPresent()) {
      criteria = new Criteria().andOperator(
          criteria,
          cursorBoundary(timestampField, direction, cursor.get()));
    }
    var query = new Query(criteria)
        .with(Sort.by(
            new Sort.Order(direction, timestampField),
            new Sort.Order(direction, "_id")))
        .limit(size + 1);
    var loaded = mongo.find(query, Post.class);
    return postPage(loaded, size, post -> timestamp(post, timestampField));
  }

  private VoidDiscoveryPage<Post> postPage(
      List<Post> loaded, int size, Function<Post, Instant> timestamp) {
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var boundary = items.get(items.size() - 1);
      nextCursor = cursors.encode(new StableCursor(timestamp.apply(boundary), boundary.getId()));
    }
    return new VoidDiscoveryPage<>(items, nextCursor);
  }

  private static Criteria rootCriteria(Instant now, boolean requireRevival) {
    var criteria = new ArrayList<Criteria>();
    criteria.add(Criteria.where("parentId").is(null));
    criteria.add(Criteria.where("expiresOn").gt(now));
    if (requireRevival) {
      criteria.add(Criteria.where("lastExtendedOn").ne(null));
    }
    return new Criteria().andOperator(criteria.toArray(Criteria[]::new));
  }

  private static Criteria cursorBoundary(
      String timestampField, Sort.Direction direction, StableCursor cursor) {
    Criteria timestampBoundary = direction.isDescending()
        ? Criteria.where(timestampField).lt(cursor.timestamp())
        : Criteria.where(timestampField).gt(cursor.timestamp());
    Criteria idBoundary = direction.isDescending()
        ? Criteria.where("_id").lt(cursor.id())
        : Criteria.where("_id").gt(cursor.id());
    return new Criteria().orOperator(
        timestampBoundary,
        new Criteria().andOperator(
            Criteria.where(timestampField).is(cursor.timestamp()),
            idBoundary));
  }

  private static Document rootMatchWithCursor(
      String timestampField,
      Sort.Direction direction,
      Optional<StableCursor> cursor,
      Instant now
  ) {
    var clauses = new java.util.ArrayList<Document>();
    clauses.add(new Document("parentId", null));
    clauses.add(new Document("expiresOn", new Document("$gt", Date.from(now))));
    cursor.ifPresent(boundary -> clauses.add(rawBoundary(timestampField, direction, boundary)));
    return new Document("$and", clauses);
  }

  private static Document rawBoundary(
      String timestampField, Sort.Direction direction, StableCursor cursor) {
    String operator = direction.isDescending() ? "$lt" : "$gt";
    return new Document("$or", List.of(
        new Document(timestampField, new Document(operator, Date.from(cursor.timestamp()))),
        new Document("$and", List.of(
            new Document(timestampField, Date.from(cursor.timestamp())),
            new Document("_id", new Document(operator, cursor.id()))))));
  }

  private static Document descendingAscendingBoundary(
      String timestampField, String idField, StableCursor cursor) {
    return new Document("$or", List.of(
        new Document(timestampField, new Document("$lt", Date.from(cursor.timestamp()))),
        new Document("$and", List.of(
            new Document(timestampField, Date.from(cursor.timestamp())),
            new Document(idField, new Document("$gt", cursor.id()))))));
  }

  private static AggregationOperation raw(String operator, Object value) {
    return context -> new Document(operator, value);
  }

  private static int pageSize(int requestedSize) {
    return Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
  }

  private static Instant timestamp(Post post, String field) {
    return switch (field) {
      case "createdOn" -> post.getCreatedOn();
      case "expiresOn" -> post.getExpiresOn();
      case "lastExtendedOn" -> post.getLastExtendedOn();
      default -> throw new IllegalArgumentException("Unsupported discovery timestamp field.");
    };
  }
}

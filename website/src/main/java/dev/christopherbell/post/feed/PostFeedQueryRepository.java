package dev.christopherbell.post.feed;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.account.follow.AccountFollow;
import dev.christopherbell.post.model.Post;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Compound Mongo queries for deterministic global and author post feeds. */
@Repository
@RequiredArgsConstructor
public class PostFeedQueryRepository {
  private static final int MAX_PAGE_SIZE = 100;
  private final MongoTemplate mongo;
  private final StableCursorCodec cursorCodec;

  /** Reads a global stable page. */
  public PostFeedSlice global(Optional<StableCursor> cursor, int requestedSize) {
    return global(cursor, requestedSize, PostFeedVisibility.unrestricted());
  }

  public PostFeedSlice global(
      Optional<StableCursor> cursor, int requestedSize, PostFeedVisibility visibility) {
    return page(new Criteria(), cursor, requestedSize, visibility);
  }

  /** Reads a stable page for one author. */
  public PostFeedSlice account(
      String accountId,
      Optional<StableCursor> cursor,
      int requestedSize
  ) {
    return account(accountId, cursor, requestedSize, PostFeedVisibility.unrestricted());
  }

  public PostFeedSlice account(
      String accountId,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility
  ) {
    return page(Criteria.where("accountId").is(accountId), cursor, requestedSize, visibility);
  }

  /** Reads a stable page for a bounded set of followed authors. */
  public PostFeedSlice accounts(
      Collection<String> accountIds,
      Optional<StableCursor> cursor,
      int requestedSize
  ) {
    return page(
        Criteria.where("accountId").in(accountIds),
        cursor,
        requestedSize,
        PostFeedVisibility.unrestricted());
  }

  /** Reads followed authors through the edge collection without materializing an ID graph. */
  public PostFeedSlice following(
      String followerId,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility
  ) {
    int size = pageSize(requestedSize);
    var criteria = visible(new Criteria(), cursor, visibility);
    var lookup = new Document("from", AccountFollow.COLLECTION)
        .append("let", new Document("authorId", "$accountId"))
        .append("pipeline", List.of(
            new Document("$match", new Document("$expr", new Document("$and", List.of(
                new Document("$eq", List.of("$followerAccountId", followerId)),
                new Document("$eq", List.of("$followedAccountId", "$$authorId")))))),
            new Document("$limit", 1)))
        .append("as", "matchingFollow");
    var aggregation = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
        context -> new Document("$match", criteria.getCriteriaObject()),
        context -> new Document("$lookup", lookup),
        context -> new Document("$match", new Document("matchingFollow.0", new Document("$exists", true))),
        context -> new Document("$sort", new Document("createdOn", -1).append("_id", -1)),
        context -> new Document("$limit", size + 1));
    return slice(mongo.aggregate(aggregation, "posts", Post.class).getMappedResults(), size);
  }

  private PostFeedSlice page(
      Criteria scope,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility
  ) {
    int size = pageSize(requestedSize);
    Criteria criteria = visible(scope, cursor, visibility);
    var query = new Query(criteria)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "_id"))
        .limit(size + 1);
    return slice(mongo.find(query, Post.class), size);
  }

  private Criteria visible(
      Criteria scope,
      Optional<StableCursor> cursor,
      PostFeedVisibility visibility
  ) {
    var clauses = new ArrayList<Criteria>();
    if (!scope.getCriteriaObject().isEmpty()) {
      clauses.add(scope);
    }
    if (cursor.isPresent()) {
      var boundary = cursor.get();
      var before = new Criteria().orOperator(
          Criteria.where("createdOn").lt(boundary.timestamp()),
          new Criteria().andOperator(
              Criteria.where("createdOn").is(boundary.timestamp()),
              Criteria.where("_id").lt(boundary.id())));
      clauses.add(before);
    }
    visibility.expiresAfter().ifPresent(cutoff ->
        clauses.add(Criteria.where("expiresOn").gt(cutoff)));
    if (!visibility.excludedAccountIds().isEmpty()) {
      clauses.add(Criteria.where("accountId").nin(visibility.excludedAccountIds()));
    }
    if (!visibility.excludedRootIds().isEmpty()) {
      clauses.add(Criteria.where("rootId").nin(visibility.excludedRootIds()));
    }
    return clauses.isEmpty()
        ? new Criteria()
        : new Criteria().andOperator(clauses.toArray(Criteria[]::new));
  }

  private PostFeedSlice slice(List<Post> loaded, int size) {
    boolean hasNext = loaded.size() > size;
    var posts = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !posts.isEmpty()) {
      var boundary = posts.get(posts.size() - 1);
      nextCursor = cursorCodec.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new PostFeedSlice(posts, nextCursor);
  }

  private static int pageSize(int requestedSize) {
    return Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
  }
}

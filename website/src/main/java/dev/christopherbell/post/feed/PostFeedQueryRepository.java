package dev.christopherbell.post.feed;

import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.util.Collection;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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
    return page(new Criteria(), cursor, requestedSize);
  }

  /** Reads a stable page for one author. */
  public PostFeedSlice account(
      String accountId,
      Optional<StableCursor> cursor,
      int requestedSize
  ) {
    return page(Criteria.where("accountId").is(accountId), cursor, requestedSize);
  }

  /** Reads a stable page for a bounded set of followed authors. */
  public PostFeedSlice accounts(
      Collection<String> accountIds,
      Optional<StableCursor> cursor,
      int requestedSize
  ) {
    return page(Criteria.where("accountId").in(accountIds), cursor, requestedSize);
  }

  private PostFeedSlice page(
      Criteria scope,
      Optional<StableCursor> cursor,
      int requestedSize
  ) {
    int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    Criteria criteria = scope;
    if (cursor.isPresent()) {
      var boundary = cursor.get();
      var before = new Criteria().orOperator(
          Criteria.where("createdOn").lt(boundary.timestamp()),
          new Criteria().andOperator(
              Criteria.where("createdOn").is(boundary.timestamp()),
              Criteria.where("_id").lt(boundary.id())));
      criteria = scope.getCriteriaObject().isEmpty()
          ? before
          : new Criteria().andOperator(scope, before);
    }
    var query = new Query(criteria)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "_id"))
        .limit(size + 1);
    var loaded = mongo.find(query, Post.class);
    boolean hasNext = loaded.size() > size;
    var posts = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !posts.isEmpty()) {
      var boundary = posts.get(posts.size() - 1);
      nextCursor = cursorCodec.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new PostFeedSlice(posts, nextCursor);
  }
}

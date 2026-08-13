package dev.christopherbell.post.like;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Mongo implementation of the post-like persistence boundary. */
@MongoPersistence
public final class MongoPostLikeStore implements PostLikeStore {
  private static final int MAX_RECENT_LIKES = 256;
  private final KindScopedMongoOperations<PostLike> mongo;

  public MongoPostLikeStore(DomainMongoOperationsFactory factory) {
    this.mongo = factory.forType(PostLike.class);
  }

  @Override
  public LikeTransition like(String postId, String accountId, Instant createdOn) {
    try {
      mongo.insert(PostLike.builder().id(PostLikeStore.edgeId(postId, accountId))
          .postId(postId).accountId(accountId).createdOn(createdOn).build());
      return new LikeTransition(true, false);
    } catch (DuplicateKeyException duplicate) {
      return new LikeTransition(false, false);
    }
  }

  @Override
  public LikeTransition unlike(String postId, String accountId) {
    var result = mongo.remove(exact(postId, accountId));
    return new LikeTransition(false, result.getDeletedCount() > 0);
  }

  @Override
  public boolean exists(String postId, String accountId) {
    return mongo.exists(exact(postId, accountId));
  }

  @Override
  public Map<String, Integer> counts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) return Map.of();
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("postId").in(postIds)),
        Aggregation.group("postId").count().as("count"));
    var counts = new LinkedHashMap<String, Integer>();
    mongo.aggregate(KindScopedAggregation.local(aggregation), CountRow.class)
        .forEach(row -> counts.put(row.id(), row.count()));
    return Map.copyOf(counts);
  }

  @Override
  public Set<String> likedPostIds(String accountId, Collection<String> postIds) {
    if (accountId == null || accountId.isBlank() || postIds == null || postIds.isEmpty()) {
      return Set.of();
    }
    var query = new Query(new Criteria().andOperator(
        Criteria.where("accountId").is(accountId), Criteria.where("postId").in(postIds)));
    var result = new LinkedHashSet<String>();
    mongo.find(query, Pageable.unpaged()).forEach(edge -> result.add(edge.getPostId()));
    return Set.copyOf(result);
  }

  @Override
  public List<String> recentLikedPostIds(String accountId) {
    var query = new Query(Criteria.where("accountId").is(accountId))
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .limit(MAX_RECENT_LIKES);
    return mongo.find(query, Pageable.unpaged()).stream().map(PostLike::getPostId).toList();
  }

  @Override
  public void deleteForAccount(String accountId) {
    mongo.remove(new Query(Criteria.where("accountId").is(accountId)));
  }

  @Override
  public void deleteForPosts(Collection<String> postIds) {
    if (postIds != null && !postIds.isEmpty()) {
      mongo.remove(new Query(Criteria.where("postId").in(postIds)));
    }
  }

  private static Query exact(String postId, String accountId) {
    return new Query(Criteria.where("id").is(PostLikeStore.edgeId(postId, accountId)));
  }

  private record CountRow(String id, int count) {}
}

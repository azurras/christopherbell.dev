package dev.christopherbell.post.like;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/** Atomic persistence and bounded aggregate queries for post-like edges. */
@Repository
@RequiredArgsConstructor
public class PostLikeStore {
  private static final int MAX_RECENT_LIKES = 256;
  private final MongoTemplate mongo;

  public LikeTransition like(String postId, String accountId, Instant createdOn) {
    var result = mongo.upsert(
        exact(postId, accountId),
        new Update()
            .setOnInsert("_id", edgeId(postId, accountId))
            .setOnInsert("postId", postId)
            .setOnInsert("accountId", accountId)
            .setOnInsert("createdOn", createdOn),
        PostLike.class);
    return new LikeTransition(result.getUpsertedId() != null, false);
  }

  public LikeTransition unlike(String postId, String accountId) {
    var result = mongo.remove(exact(postId, accountId), PostLike.class);
    return new LikeTransition(false, result.getDeletedCount() > 0);
  }

  public boolean exists(String postId, String accountId) {
    return mongo.exists(exact(postId, accountId), PostLike.class);
  }

  public Map<String, Integer> counts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) {
      return Map.of();
    }
    var aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("postId").in(postIds)),
        Aggregation.group("postId").count().as("count"));
    var counts = new LinkedHashMap<String, Integer>();
    mongo.aggregate(aggregation, PostLike.COLLECTION, CountRow.class)
        .getMappedResults()
        .forEach(row -> counts.put(row.id(), row.count()));
    return Map.copyOf(counts);
  }

  public Set<String> likedPostIds(String accountId, Collection<String> postIds) {
    if (accountId == null || accountId.isBlank() || postIds == null || postIds.isEmpty()) {
      return Set.of();
    }
    var query = new Query(new Criteria().andOperator(
        Criteria.where("accountId").is(accountId),
        Criteria.where("postId").in(postIds)));
    query.fields().include("postId");
    var result = new LinkedHashSet<String>();
    mongo.find(query, PostLike.class).forEach(edge -> result.add(edge.getPostId()));
    return Set.copyOf(result);
  }

  public List<String> recentLikedPostIds(String accountId) {
    var query = new Query(Criteria.where("accountId").is(accountId))
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "_id"))
        .limit(MAX_RECENT_LIKES);
    query.fields().include("postId");
    return mongo.find(query, PostLike.class).stream().map(PostLike::getPostId).toList();
  }

  public void deleteForAccount(String accountId) {
    mongo.remove(new Query(Criteria.where("accountId").is(accountId)), PostLike.class);
  }

  public void deleteForPosts(Collection<String> postIds) {
    if (postIds != null && !postIds.isEmpty()) {
      mongo.remove(new Query(Criteria.where("postId").in(postIds)), PostLike.class);
    }
  }

  public static String edgeId(String postId, String accountId) {
    return postId + ":" + accountId;
  }

  private static Query exact(String postId, String accountId) {
    return new Query(Criteria.where("_id").is(edgeId(postId, accountId)));
  }

  public record LikeTransition(boolean created, boolean removed) {
    public int delta() {
      return created ? 1 : removed ? -1 : 0;
    }
  }

  private record CountRow(String id, int count) {}
}

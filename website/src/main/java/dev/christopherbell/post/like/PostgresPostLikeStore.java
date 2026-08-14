package dev.christopherbell.post.like;

import static dev.christopherbell.persistence.jooq.social.Tables.POST_LIKE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;

/** PostgreSQL implementation of the post-like persistence boundary. */
@PostgresPersistence
public class PostgresPostLikeStore implements PostLikeStore {
  private static final int MAX_RECENT_LIKES = 256;
  private final DSLContext database;

  public PostgresPostLikeStore(DSLContext database) {
    this.database = database;
  }

  @Override
  public LikeTransition like(String postId, String accountId, Instant createdOn) {
    var inserted = database.insertInto(POST_LIKE)
        .set(POST_LIKE.POST_LIKE_ID, PostLikeStore.edgeId(postId, accountId))
        .set(POST_LIKE.POST_ID, postId)
        .set(POST_LIKE.ACCOUNT_ID, accountId)
        .set(POST_LIKE.CREATED_ON, createdOn == null ? null : createdOn.atOffset(ZoneOffset.UTC))
        .onConflict(POST_LIKE.POST_ID, POST_LIKE.ACCOUNT_ID)
        .doNothing()
        .execute();
    return new LikeTransition(inserted == 1, false);
  }

  @Override
  public LikeTransition unlike(String postId, String accountId) {
    var removed = database.deleteFrom(POST_LIKE)
        .where(POST_LIKE.POST_ID.eq(postId).and(POST_LIKE.ACCOUNT_ID.eq(accountId)))
        .execute();
    return new LikeTransition(false, removed > 0);
  }

  @Override
  public boolean exists(String postId, String accountId) {
    return database.fetchExists(
        POST_LIKE, POST_LIKE.POST_ID.eq(postId).and(POST_LIKE.ACCOUNT_ID.eq(accountId)));
  }

  @Override
  public Map<String, Integer> counts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) return Map.of();
    var result = new LinkedHashMap<String, Integer>();
    database.select(POST_LIKE.POST_ID, org.jooq.impl.DSL.count())
        .from(POST_LIKE)
        .where(POST_LIKE.POST_ID.in(postIds))
        .groupBy(POST_LIKE.POST_ID)
        .fetch()
        .forEach(row -> result.put(row.value1(), row.value2()));
    return Map.copyOf(result);
  }

  @Override
  public Set<String> likedPostIds(String accountId, Collection<String> postIds) {
    if (accountId == null || accountId.isBlank() || postIds == null || postIds.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(new LinkedHashSet<>(database.select(POST_LIKE.POST_ID)
        .from(POST_LIKE)
        .where(POST_LIKE.ACCOUNT_ID.eq(accountId).and(POST_LIKE.POST_ID.in(postIds)))
        .fetch(POST_LIKE.POST_ID)));
  }

  @Override
  public List<String> recentLikedPostIds(String accountId) {
    return database.select(POST_LIKE.POST_ID)
        .from(POST_LIKE)
        .where(POST_LIKE.ACCOUNT_ID.eq(accountId))
        .orderBy(POST_LIKE.CREATED_ON.desc().nullsLast(), POST_LIKE.POST_LIKE_ID.desc())
        .limit(MAX_RECENT_LIKES)
        .fetch(POST_LIKE.POST_ID);
  }

  @Override
  public void deleteForAccount(String accountId) {
    database.deleteFrom(POST_LIKE).where(POST_LIKE.ACCOUNT_ID.eq(accountId)).execute();
  }

  @Override
  public void deleteForPosts(Collection<String> postIds) {
    if (postIds != null && !postIds.isEmpty()) {
      database.deleteFrom(POST_LIKE).where(POST_LIKE.POST_ID.in(postIds)).execute();
    }
  }
}

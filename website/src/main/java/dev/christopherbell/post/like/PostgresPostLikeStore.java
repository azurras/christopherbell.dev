package dev.christopherbell.post.like;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of the post-like persistence boundary. */
@PostgresPersistence
public class PostgresPostLikeStore implements PostLikeStore {
  private static final int MAX_RECENT_LIKES = 256;
  private final JdbcClient database;
  private final String table;

  public PostgresPostLikeStore(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("social", "post_like");
  }

  @Override
  public LikeTransition like(String postId, String accountId, Instant createdOn) {
    var statement = database.sql("""
            insert into %s (post_like_id, post_id, account_id, created_on)
            values (:id, :postId, :accountId, :createdOn)
            on conflict (post_id, account_id) do nothing
            """.formatted(table))
        .param("id", PostLikeStore.edgeId(postId, accountId))
        .param("postId", postId)
        .param("accountId", accountId);
    var inserted = (createdOn == null
        ? statement.param("createdOn", null, Types.TIMESTAMP_WITH_TIMEZONE)
        : statement.param("createdOn", createdOn.atOffset(ZoneOffset.UTC))).update();
    return new LikeTransition(inserted == 1, false);
  }

  @Override
  public LikeTransition unlike(String postId, String accountId) {
    var removed = database.sql("delete from %s where post_id = :postId and account_id = :accountId"
            .formatted(table))
        .param("postId", postId).param("accountId", accountId).update();
    return new LikeTransition(false, removed > 0);
  }

  @Override
  public boolean exists(String postId, String accountId) {
    return database.sql("""
            select exists (select 1 from %s where post_id = :postId and account_id = :accountId)
            """.formatted(table))
        .param("postId", postId).param("accountId", accountId)
        .query(Boolean.class).single();
  }

  @Override
  public Map<String, Integer> counts(Collection<String> postIds) {
    if (postIds == null || postIds.isEmpty()) return Map.of();
    var result = new LinkedHashMap<String, Integer>();
    database.sql("""
            select post_id, count(*)::integer as like_count from %s
            where post_id in (:postIds) group by post_id order by post_id
            """.formatted(table))
        .param("postIds", postIds)
        .query((row, ignored) -> Map.entry(row.getString("post_id"), row.getInt("like_count")))
        .list().forEach(value -> result.put(value.getKey(), value.getValue()));
    return Map.copyOf(result);
  }

  @Override
  public Set<String> likedPostIds(String accountId, Collection<String> postIds) {
    if (accountId == null || accountId.isBlank() || postIds == null || postIds.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(new LinkedHashSet<>(database.sql("""
            select post_id from %s where account_id = :accountId and post_id in (:postIds)
            order by post_id
            """.formatted(table))
        .param("accountId", accountId).param("postIds", postIds)
        .query(String.class).list()));
  }

  @Override
  public List<String> recentLikedPostIds(String accountId) {
    return database.sql("""
            select post_id from %s where account_id = :accountId
            order by created_on desc nulls last, post_like_id desc limit :limit
            """.formatted(table))
        .param("accountId", accountId).param("limit", MAX_RECENT_LIKES)
        .query(String.class).list();
  }

  @Override
  public void deleteForAccount(String accountId) {
    database.sql("delete from %s where account_id = :accountId".formatted(table))
        .param("accountId", accountId).update();
  }

  @Override
  public void deleteForPosts(Collection<String> postIds) {
    if (postIds != null && !postIds.isEmpty()) {
      database.sql("delete from %s where post_id in (:postIds)".formatted(table))
          .param("postIds", postIds).update();
    }
  }
}

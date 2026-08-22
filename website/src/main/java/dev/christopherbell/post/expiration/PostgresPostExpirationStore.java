package dev.christopherbell.post.expiration;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.post.PostgresPostMapper;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL atomic storage effects for post expiration. */
@PostgresPersistence
public class PostgresPostExpirationStore implements PostExpirationStore {
  private final JdbcClient database;
  private final PostgresPostMapper mapper;
  private final String table;

  public PostgresPostExpirationStore(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    mapper = new PostgresPostMapper(database, schemas);
    table = schemas.qualifiedTable("social", "post");
  }

  @Override
  public void synchronizeReplies(String rootId, String rootPostId, Instant expiresOn) {
    database.sql("""
            update %s set expires_on = :expiresOn, version = version + 1
            where root_post_id = :rootId and post_id <> :rootPostId and parent_post_id is not null
            """.formatted(table)).param("expiresOn", timestamp(expiresOn))
        .param("rootId", rootId).param("rootPostId", rootPostId).update();
  }

  @Override
  public Optional<Post> incrementCounter(
      String postId, String field, int delta, Instant changedOn, boolean extended) {
    String counter = counter(field);
    String condition = delta < 0 ? " and %s > 0".formatted(counter) : "";
    var rows = database.sql("""
            update %s set %s = greatest(0, %s + :delta), last_updated_on = :changedOn,
              last_extended_on = case when :extended then :changedOn else last_extended_on end,
              version = version + 1
            where post_id = :id%s returning *
            """.formatted(table, counter, counter, condition))
        .param("delta", delta).param("changedOn", timestamp(changedOn))
        .param("extended", extended).param("id", postId).query(mapper::row).list();
    if (!rows.isEmpty()) return mapper.mapAll(rows).stream().findFirst();
    return find(postId);
  }

  @Override
  public long deletePosts(List<String> postIds) {
    if (postIds.isEmpty()) return 0;
    return database.sql("delete from %s where post_id in (:ids)".formatted(table))
        .param("ids", postIds).update();
  }

  @Override
  public Optional<Post> decrementFloorZero(
      String postId, String field, int delta, Instant changedOn) {
    String counter = counter(field);
    var rows = database.sql("""
            update %s set %s = greatest(0, %s - :delta), last_updated_on = :changedOn,
              version = version + 1 where post_id = :id returning *
            """.formatted(table, counter, counter)).param("delta", delta)
        .param("changedOn", timestamp(changedOn)).param("id", postId)
        .query(mapper::row).list();
    return mapper.mapAll(rows).stream().findFirst();
  }

  @Override
  public void updateExpiration(String postId, Instant expiresOn) {
    database.sql("""
            update %s set expires_on = :expiresOn, version = version + 1 where post_id = :id
            """.formatted(table)).param("expiresOn", timestamp(expiresOn))
        .param("id", postId).update();
  }

  private Optional<Post> find(String id) {
    var rows = database.sql("select * from %s where post_id = :id".formatted(table))
        .param("id", id).query(mapper::row).list();
    return mapper.mapAll(rows).stream().findFirst();
  }

  private static String counter(String field) {
    return switch (field) {
      case "likesCount" -> "likes_count";
      case "threadReplyLikesCount" -> "thread_reply_likes_count";
      case "threadReplyCount" -> "thread_reply_count";
      default -> throw new IllegalArgumentException("Unsupported post counter.");
    };
  }

  private static java.time.OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
}

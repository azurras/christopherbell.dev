package dev.christopherbell.post.expiration;

import static dev.christopherbell.persistence.jooq.social.Tables.POST;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.post.PostgresPostMapper;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** PostgreSQL atomic storage effects for post expiration. */
@PostgresPersistence
public final class PostgresPostExpirationStore implements PostExpirationStore {
  private final DSLContext database;

  public PostgresPostExpirationStore(DSLContext database) {
    this.database = database;
  }

  @Override
  public void synchronizeReplies(String rootId, String rootPostId, Instant expiresOn) {
    database.update(POST)
        .set(POST.EXPIRES_ON, timestamp(expiresOn))
        .set(POST.VERSION, POST.VERSION.plus(1L))
        .where(POST.ROOT_POST_ID.eq(rootId)
            .and(POST.POST_ID.ne(rootPostId))
            .and(POST.PARENT_POST_ID.isNotNull()))
        .execute();
  }

  @Override
  public Optional<Post> incrementCounter(
      String postId, String field, int delta, Instant changedOn, boolean extended) {
    var counter = counter(field);
    var condition = POST.POST_ID.eq(postId);
    if (delta < 0) condition = condition.and(counter.gt(0));
    var update = database.update(POST)
        .set(counter, DSL.greatest(DSL.inline(0), counter.plus(delta)))
        .set(POST.LAST_UPDATED_ON, timestamp(changedOn))
        .set(POST.VERSION, POST.VERSION.plus(1L));
    if (extended) update.set(POST.LAST_EXTENDED_ON, timestamp(changedOn));
    var updated = update.where(condition).returning().fetchOne();
    if (updated != null) return Optional.of(PostgresPostMapper.map(database, updated));
    return database.selectFrom(POST).where(POST.POST_ID.eq(postId))
        .fetchOptional(record -> PostgresPostMapper.map(database, record));
  }

  @Override
  public long deletePosts(List<String> postIds) {
    if (postIds.isEmpty()) return 0;
    return database.deleteFrom(POST).where(POST.POST_ID.in(postIds)).execute();
  }

  @Override
  public Optional<Post> decrementFloorZero(
      String postId, String field, int delta, Instant changedOn) {
    var counter = counter(field);
    var updated = database.update(POST)
        .set(counter, DSL.greatest(DSL.inline(0), counter.minus(delta)))
        .set(POST.LAST_UPDATED_ON, timestamp(changedOn))
        .set(POST.VERSION, POST.VERSION.plus(1L))
        .where(POST.POST_ID.eq(postId))
        .returning()
        .fetchOne();
    return Optional.ofNullable(updated)
        .map(record -> PostgresPostMapper.map(database, record));
  }

  @Override
  public void updateExpiration(String postId, Instant expiresOn) {
    database.update(POST)
        .set(POST.EXPIRES_ON, timestamp(expiresOn))
        .set(POST.VERSION, POST.VERSION.plus(1L))
        .where(POST.POST_ID.eq(postId))
        .execute();
  }

  private static Field<Integer> counter(String field) {
    return switch (field) {
      case "likesCount" -> POST.LIKES_COUNT;
      case "threadReplyLikesCount" -> POST.THREAD_REPLY_LIKES_COUNT;
      case "threadReplyCount" -> POST.THREAD_REPLY_COUNT;
      default -> throw new IllegalArgumentException("Unsupported post counter.");
    };
  }

  private static java.time.OffsetDateTime timestamp(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }
}

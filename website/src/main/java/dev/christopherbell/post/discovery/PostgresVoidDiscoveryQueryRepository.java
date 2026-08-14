package dev.christopherbell.post.discovery;

import static dev.christopherbell.persistence.jooq.social.Tables.POST;
import static dev.christopherbell.persistence.jooq.social.Tables.POST_TOPIC;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.persistence.jooq.social.tables.records.PostRecord;
import dev.christopherbell.post.PostgresPostMapper;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.jooq.TableField;
import org.jooq.impl.DSL;

/** PostgreSQL anonymous Void discovery with stable keyset cursors. */
@PostgresPersistence
public class PostgresVoidDiscoveryQueryRepository implements VoidDiscoveryQueryPort {
  private static final int MAX_PAGE_SIZE = 24;
  private final DSLContext database;
  private final StableCursorCodec cursors;

  public PostgresVoidDiscoveryQueryRepository(DSLContext database, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
  }

  @Override
  public VoidDiscoveryPage<Post> newArrivals(
      Optional<StableCursor> cursor, int size, Instant now) {
    return rootPage(POST.CREATED_ON, SortOrder.DESC, cursor, size, now, false,
        Post::getCreatedOn);
  }

  @Override
  public VoidDiscoveryPage<Post> fadingSoon(
      Optional<StableCursor> cursor, int size, Instant now) {
    return rootPage(POST.EXPIRES_ON, SortOrder.ASC, cursor, size, now, false,
        Post::getExpiresOn);
  }

  @Override
  public VoidDiscoveryPage<Post> recentlyRevived(
      Optional<StableCursor> cursor, int size, Instant now) {
    return rootPage(POST.LAST_EXTENDED_ON, SortOrder.DESC, cursor, size, now, true,
        Post::getLastExtendedOn);
  }

  @Override
  public VoidDiscoveryPage<Post> topic(
      String canonical, Optional<StableCursor> cursor, int requestedSize, Instant now) {
    var matched = POST.as("matched_post");
    var matchedTopic = POST_TOPIC.as("matched_topic");
    var rootIds = DSL.selectDistinct(matched.ROOT_POST_ID)
        .from(matched)
        .join(matchedTopic).on(matchedTopic.POST_ID.eq(matched.POST_ID))
        .where(matched.EXPIRES_ON.gt(timestamp(now))
            .and(matchedTopic.CANONICAL.eq(canonical)));
    int size = pageSize(requestedSize);
    var condition = POST.POST_ID.in(rootIds)
        .and(POST.PARENT_POST_ID.isNull())
        .and(POST.EXPIRES_ON.gt(timestamp(now)))
        .and(boundary(POST.CREATED_ON, SortOrder.DESC, cursor));
    var loaded = database.selectFrom(POST).where(condition)
        .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc())
        .limit(size + 1)
        .fetch();
    return postPage(PostgresPostMapper.mapAll(database, loaded), size, Post::getCreatedOn);
  }

  @Override
  public VoidDiscoveryPage<VoidTopicSummary> topics(
      Optional<StableCursor> cursor, int requestedSize, Instant now) {
    int size = pageSize(requestedSize);
    var activity = DSL.max(DSL.coalesce(POST.LAST_EXTENDED_ON, POST.CREATED_ON));
    var display = DSL.min(POST_TOPIC.DISPLAY);
    Condition having = DSL.noCondition();
    if (cursor.isPresent()) {
      var value = timestamp(cursor.get().timestamp());
      having = activity.lt(value).or(activity.eq(value)
          .and(POST_TOPIC.CANONICAL.gt(cursor.get().id())));
    }
    var loaded = database.select(POST_TOPIC.CANONICAL, display, activity)
        .from(POST_TOPIC)
        .join(POST).on(POST.POST_ID.eq(POST_TOPIC.POST_ID))
        .where(POST.EXPIRES_ON.gt(timestamp(now)))
        .groupBy(POST_TOPIC.CANONICAL)
        .having(having)
        .orderBy(activity.desc(), POST_TOPIC.CANONICAL.asc())
        .limit(size + 1)
        .fetch(record -> new VoidTopicSummary(
            record.value1(), record.value2(), record.value3().toInstant()));
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String next = null;
    if (hasNext && !items.isEmpty()) {
      var last = items.get(items.size() - 1);
      next = cursors.encode(new StableCursor(last.activityOn(), last.canonical()));
    }
    return new VoidDiscoveryPage<>(items, next);
  }

  private VoidDiscoveryPage<Post> rootPage(
      TableField<PostRecord, OffsetDateTime> field,
      SortOrder direction,
      Optional<StableCursor> cursor,
      int requestedSize,
      Instant now,
      boolean requireRevival,
      Function<Post, Instant> timestamp
  ) {
    int size = pageSize(requestedSize);
    var condition = POST.PARENT_POST_ID.isNull()
        .and(POST.EXPIRES_ON.gt(timestamp(now)))
        .and(boundary(field, direction, cursor));
    if (requireRevival) condition = condition.and(POST.LAST_EXTENDED_ON.isNotNull());
    var loaded = database.selectFrom(POST).where(condition)
        .orderBy(field.sort(direction), POST.POST_ID.sort(direction))
        .limit(size + 1)
        .fetch();
    return postPage(PostgresPostMapper.mapAll(database, loaded), size, timestamp);
  }

  private VoidDiscoveryPage<Post> postPage(
      List<Post> loaded, int size, Function<Post, Instant> timestamp) {
    boolean hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String next = null;
    if (hasNext && !items.isEmpty()) {
      var last = items.get(items.size() - 1);
      next = cursors.encode(new StableCursor(timestamp.apply(last), last.getId()));
    }
    return new VoidDiscoveryPage<>(items, next);
  }

  private static Condition boundary(
      TableField<PostRecord, OffsetDateTime> field,
      SortOrder direction,
      Optional<StableCursor> cursor
  ) {
    if (cursor.isEmpty()) return DSL.noCondition();
    var value = timestamp(cursor.get().timestamp());
    return direction == SortOrder.DESC
        ? field.lt(value).or(field.eq(value).and(POST.POST_ID.lt(cursor.get().id())))
        : field.gt(value).or(field.eq(value).and(POST.POST_ID.gt(cursor.get().id())));
  }

  private static int pageSize(int requested) {
    return Math.max(1, Math.min(requested, MAX_PAGE_SIZE));
  }

  private static OffsetDateTime timestamp(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }
}

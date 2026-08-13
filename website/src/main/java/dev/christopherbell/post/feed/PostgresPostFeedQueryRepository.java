package dev.christopherbell.post.feed;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_FOLLOW;
import static dev.christopherbell.persistence.jooq.social.Tables.POST;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostgresPostMapper;
import dev.christopherbell.post.model.Post;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL keyset queries for deterministic global, author, and following feeds. */
@PostgresPersistence
public final class PostgresPostFeedQueryRepository implements PostFeedQueryPort {
  private static final int MAX_PAGE_SIZE = 100;
  private final DSLContext database;
  private final StableCursorCodec cursors;

  public PostgresPostFeedQueryRepository(DSLContext database, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
  }

  @Override
  public PostFeedSlice global(Optional<StableCursor> cursor, int requestedSize) {
    return global(cursor, requestedSize, PostFeedVisibility.unrestricted());
  }

  @Override
  public PostFeedSlice global(
      Optional<StableCursor> cursor, int requestedSize, PostFeedVisibility visibility) {
    return page(DSL.trueCondition(), cursor, requestedSize, visibility);
  }

  @Override
  public PostFeedSlice account(
      String accountId, Optional<StableCursor> cursor, int requestedSize) {
    return account(accountId, cursor, requestedSize, PostFeedVisibility.unrestricted());
  }

  @Override
  public PostFeedSlice account(
      String accountId,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility) {
    return page(POST.ACCOUNT_ID.eq(accountId), cursor, requestedSize, visibility);
  }

  @Override
  public PostFeedSlice accounts(
      Collection<String> accountIds, Optional<StableCursor> cursor, int requestedSize) {
    return page(accountIds.isEmpty() ? DSL.falseCondition() : POST.ACCOUNT_ID.in(accountIds),
        cursor, requestedSize, PostFeedVisibility.unrestricted());
  }

  @Override
  public PostFeedSlice following(
      String followerId,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility) {
    var followed = database.select(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID)
        .from(ACCOUNT_FOLLOW)
        .where(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID.eq(followerId));
    return page(POST.ACCOUNT_ID.in(followed), cursor, requestedSize, visibility);
  }

  private PostFeedSlice page(
      Condition scope,
      Optional<StableCursor> cursor,
      int requestedSize,
      PostFeedVisibility visibility) {
    var condition = scope.and(visible(cursor, visibility));
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var loaded = database.selectFrom(POST)
        .where(condition)
        .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc())
        .limit(size + 1)
        .fetch(record -> PostgresPostMapper.map(database, record));
    return slice(loaded, size);
  }

  private static Condition visible(
      Optional<StableCursor> cursor, PostFeedVisibility visibility) {
    var clauses = new ArrayList<Condition>();
    cursor.ifPresent(boundary -> clauses.add(
        POST.CREATED_ON.lt(boundary.timestamp().atOffset(ZoneOffset.UTC))
            .or(POST.CREATED_ON.eq(boundary.timestamp().atOffset(ZoneOffset.UTC))
                .and(POST.POST_ID.lt(boundary.id())))));
    visibility.expiresAfter().ifPresent(cutoff ->
        clauses.add(POST.EXPIRES_ON.gt(cutoff.atOffset(ZoneOffset.UTC))));
    if (!visibility.excludedAccountIds().isEmpty()) {
      clauses.add(POST.ACCOUNT_ID.notIn(visibility.excludedAccountIds()));
    }
    if (!visibility.excludedRootIds().isEmpty()) {
      clauses.add(POST.ROOT_POST_ID.notIn(visibility.excludedRootIds()));
    }
    return clauses.stream().reduce(DSL.trueCondition(), Condition::and);
  }

  private PostFeedSlice slice(List<Post> loaded, int size) {
    var hasNext = loaded.size() > size;
    var items = loaded.stream().limit(size).toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var boundary = items.getLast();
      nextCursor = cursors.encode(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
    }
    return new PostFeedSlice(items, nextCursor);
  }
}

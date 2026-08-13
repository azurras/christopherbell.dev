package dev.christopherbell.federation.discovery;

import static dev.christopherbell.persistence.jooq.social.Tables.POST;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** PostgreSQL active-post queries for a local actor public outbox. */
@PostgresPersistence
public final class PostgresFederationOutboxQueryRepository
    implements FederationOutboxQueryPort {
  private static final int MAX_PAGE_SIZE = 20;
  private final DSLContext database;
  private final StableCursorCodec cursors;
  private final PostRepository posts;

  public PostgresFederationOutboxQueryRepository(
      DSLContext database, StableCursorCodec cursors, PostRepository posts) {
    this.database = database;
    this.cursors = cursors;
    this.posts = posts;
  }

  @Override
  public FederationPage<FederationOutboxEntry> page(
    String accountId, Optional<StableCursor> cursor, int requestedSize, Instant now) {
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var boundary = cursor.orElse(null);
    var mapped = posts.findFederationOutboxPage(
        accountId,
        boundary == null ? null : boundary.timestamp(),
        boundary == null ? null : boundary.id(),
        size + 1,
        now);
    var hasNext = mapped.size() > size;
    var items = mapped.stream().limit(size)
        .map(PostgresFederationOutboxQueryRepository::entry)
        .toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var nextBoundary = items.getLast();
      nextCursor = cursors.encode(
          new StableCursor(nextBoundary.createdOn(), nextBoundary.id()));
    }
    return new FederationPage<>(items, nextCursor);
  }

  private static FederationOutboxEntry entry(Post post) {
    return new FederationOutboxEntry(
        post.getId(),
        post.getText(),
        post.getParentId(),
        post.getCreatedOn(),
        post.getLastUpdatedOn());
  }

  @Override
  public long count(String accountId, Instant now) {
    return database.fetchCount(POST, activeOwned(accountId, now));
  }

  private static Condition activeOwned(String accountId, Instant now) {
    return POST.ACCOUNT_ID.eq(accountId)
        .and(POST.FEDERATION_OUTBOUND_ELIGIBLE.isTrue())
        .and(POST.EXPIRES_ON.gt(now.atOffset(ZoneOffset.UTC)))
        .and(POST.CREATED_ON.isNotNull());
  }
}

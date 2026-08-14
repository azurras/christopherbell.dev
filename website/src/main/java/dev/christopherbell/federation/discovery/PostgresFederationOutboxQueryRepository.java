package dev.christopherbell.federation.discovery;

import static dev.christopherbell.persistence.jooq.social.Tables.POST;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
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

  public PostgresFederationOutboxQueryRepository(
      DSLContext database, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
  }

  @Override
  public FederationPage<FederationOutboxEntry> page(
    String accountId, Optional<StableCursor> cursor, int requestedSize, Instant now) {
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var boundary = cursor.orElse(null);
    var condition = activeOwned(accountId, now);
    if (boundary != null) {
      var timestamp = boundary.timestamp().atOffset(ZoneOffset.UTC);
      condition = condition.and(POST.CREATED_ON.lt(timestamp)
          .or(POST.CREATED_ON.eq(timestamp).and(POST.POST_ID.lt(boundary.id()))));
    }
    var mapped = database.select(
            POST.POST_ID, POST.POST_TEXT, POST.PARENT_POST_ID,
            POST.CREATED_ON, POST.LAST_UPDATED_ON)
        .from(POST)
        .where(condition)
        .orderBy(POST.CREATED_ON.desc(), POST.POST_ID.desc())
        .limit(size + 1)
        .fetch(row -> new FederationOutboxEntry(
            row.value1(), row.value2(), row.value3(),
            row.value4().toInstant(),
            row.value5() == null ? null : row.value5().toInstant()));
    var hasNext = mapped.size() > size;
    var items = mapped.stream().limit(size)
        .toList();
    String nextCursor = null;
    if (hasNext && !items.isEmpty()) {
      var nextBoundary = items.getLast();
      nextCursor = cursors.encode(
          new StableCursor(nextBoundary.createdOn(), nextBoundary.id()));
    }
    return new FederationPage<>(items, nextCursor);
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

package dev.christopherbell.post.hide;

import static dev.christopherbell.persistence.jooq.social.Tables.HIDDEN_POST_THREAD;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL persistence for per-account hidden post threads. */
@PostgresPersistence
public final class PostgresHiddenPostThreadRepository implements HiddenPostThreadRepository {
  private final DSLContext database;

  public PostgresHiddenPostThreadRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public HiddenPostThread save(HiddenPostThread hiddenThread) {
    database.insertInto(HIDDEN_POST_THREAD)
        .set(HIDDEN_POST_THREAD.HIDDEN_POST_THREAD_ID, hiddenThread.getId())
        .set(HIDDEN_POST_THREAD.ACCOUNT_ID, hiddenThread.getAccountId())
        .set(HIDDEN_POST_THREAD.ROOT_POST_ID, hiddenThread.getRootPostId())
        .set(HIDDEN_POST_THREAD.CREATED_ON, hiddenThread.getCreatedOn() == null
            ? null : hiddenThread.getCreatedOn().atOffset(ZoneOffset.UTC))
        .onConflict(HIDDEN_POST_THREAD.ACCOUNT_ID, HIDDEN_POST_THREAD.ROOT_POST_ID)
        .doUpdate()
        .set(HIDDEN_POST_THREAD.CREATED_ON, hiddenThread.getCreatedOn() == null
            ? null : hiddenThread.getCreatedOn().atOffset(ZoneOffset.UTC))
        .set(HIDDEN_POST_THREAD.VERSION, HIDDEN_POST_THREAD.VERSION.plus(1L))
        .execute();
    return findByAccountIdAndRootPostId(
        hiddenThread.getAccountId(), hiddenThread.getRootPostId()).orElseThrow();
  }

  @Override
  public Optional<HiddenPostThread> findByAccountIdAndRootPostId(
      String accountId, String rootPostId) {
    return database.selectFrom(HIDDEN_POST_THREAD)
        .where(HIDDEN_POST_THREAD.ACCOUNT_ID.eq(accountId)
            .and(HIDDEN_POST_THREAD.ROOT_POST_ID.eq(rootPostId)))
        .fetchOptional(PostgresHiddenPostThreadRepository::map);
  }

  @Override
  public List<HiddenPostThread> findByAccountId(String accountId) {
    return database.selectFrom(HIDDEN_POST_THREAD)
        .where(HIDDEN_POST_THREAD.ACCOUNT_ID.eq(accountId))
        .fetch(PostgresHiddenPostThreadRepository::map);
  }

  @Override
  public void deleteByAccountIdAndRootPostId(String accountId, String rootPostId) {
    database.deleteFrom(HIDDEN_POST_THREAD)
        .where(HIDDEN_POST_THREAD.ACCOUNT_ID.eq(accountId)
            .and(HIDDEN_POST_THREAD.ROOT_POST_ID.eq(rootPostId)))
        .execute();
  }

  private static HiddenPostThread map(
      dev.christopherbell.persistence.jooq.social.tables.records.HiddenPostThreadRecord record) {
    return HiddenPostThread.builder()
        .id(record.getHiddenPostThreadId())
        .accountId(record.getAccountId())
        .rootPostId(record.getRootPostId())
        .createdOn(instant(record.getCreatedOn()))
        .build();
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

package dev.christopherbell.account.follow;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT_FOLLOW;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.data.domain.Pageable;

/** PostgreSQL implementation of the account-follow persistence boundary. */
@PostgresPersistence
public final class PostgresAccountFollowStore implements AccountFollowStore {
  private final DSLContext database;

  public PostgresAccountFollowStore(DSLContext database) {
    this.database = database;
  }

  @Override
  public FollowTransition follow(String followerId, String followedId, Instant createdOn) {
    var inserted = database.insertInto(ACCOUNT_FOLLOW)
        .set(ACCOUNT_FOLLOW.ACCOUNT_FOLLOW_ID, AccountFollowStore.edgeId(followerId, followedId))
        .set(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID, followerId)
        .set(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID, followedId)
        .set(ACCOUNT_FOLLOW.CREATED_ON,
            createdOn == null ? null : createdOn.atOffset(ZoneOffset.UTC))
        .onConflict(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID, ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID)
        .doNothing()
        .execute();
    return new FollowTransition(inserted == 1, false);
  }

  @Override
  public FollowTransition unfollow(String followerId, String followedId) {
    var removed = database.deleteFrom(ACCOUNT_FOLLOW)
        .where(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID.eq(followerId)
            .and(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID.eq(followedId)))
        .execute();
    return new FollowTransition(false, removed > 0);
  }

  @Override
  public boolean exists(String followerId, String followedId) {
    return database.fetchExists(ACCOUNT_FOLLOW,
        ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID.eq(followerId)
            .and(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID.eq(followedId)));
  }

  @Override
  public long countFollowing(String accountId) {
    return database.fetchCount(
        ACCOUNT_FOLLOW, ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID.eq(accountId));
  }

  @Override
  public long countFollowers(String accountId) {
    return database.fetchCount(
        ACCOUNT_FOLLOW, ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID.eq(accountId));
  }

  @Override
  public List<String> followedAccountIds(String accountId, Pageable page) {
    return page(database.select(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID)
        .from(ACCOUNT_FOLLOW)
        .where(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID.eq(accountId))
        .orderBy(ACCOUNT_FOLLOW.CREATED_ON.asc().nullsFirst(),
            ACCOUNT_FOLLOW.ACCOUNT_FOLLOW_ID.asc()), page)
        .fetch(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID);
  }

  @Override
  public List<String> followerAccountIds(String accountId, Pageable page) {
    return page(database.select(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID)
        .from(ACCOUNT_FOLLOW)
        .where(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID.eq(accountId))
        .orderBy(ACCOUNT_FOLLOW.CREATED_ON.asc().nullsFirst(),
            ACCOUNT_FOLLOW.ACCOUNT_FOLLOW_ID.asc()), page)
        .fetch(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID);
  }

  private static <R extends org.jooq.Record> org.jooq.ResultQuery<R> page(
      org.jooq.SelectLimitStep<R> query, Pageable page) {
    return query.limit(page.isPaged() ? page.getPageSize() : Integer.MAX_VALUE)
        .offset(page.isPaged() ? Math.toIntExact(page.getOffset()) : 0);
  }

  @Override
  public void deleteForAccount(String accountId) {
    database.deleteFrom(ACCOUNT_FOLLOW)
        .where(ACCOUNT_FOLLOW.FOLLOWER_ACCOUNT_ID.eq(accountId)
            .or(ACCOUNT_FOLLOW.FOLLOWED_ACCOUNT_ID.eq(accountId)))
        .execute();
  }
}

package dev.christopherbell.account.follow;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.domain.Pageable;

/** PostgreSQL implementation of the account-follow persistence boundary. */
@PostgresPersistence
public class PostgresAccountFollowStore implements AccountFollowStore {
  private final JdbcClient database;
  private final String table;

  public PostgresAccountFollowStore(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("identity", "account_follow");
  }

  @Override
  public FollowTransition follow(String followerId, String followedId, Instant createdOn) {
    var statement = database.sql("""
            insert into %s
              (account_follow_id, follower_account_id, followed_account_id, created_on)
            values (:id, :followerId, :followedId, :createdOn)
            on conflict (follower_account_id, followed_account_id) do nothing
            """.formatted(table))
        .param("id", AccountFollowStore.edgeId(followerId, followedId))
        .param("followerId", followerId)
        .param("followedId", followedId);
    var inserted = (createdOn == null
        ? statement.param("createdOn", null, Types.TIMESTAMP_WITH_TIMEZONE)
        : statement.param("createdOn", createdOn.atOffset(ZoneOffset.UTC)))
        .update();
    return new FollowTransition(inserted == 1, false);
  }

  @Override
  public FollowTransition unfollow(String followerId, String followedId) {
    var removed = database.sql("""
            delete from %s
            where follower_account_id = :followerId and followed_account_id = :followedId
            """.formatted(table))
        .param("followerId", followerId)
        .param("followedId", followedId)
        .update();
    return new FollowTransition(false, removed > 0);
  }

  @Override
  public boolean exists(String followerId, String followedId) {
    return database.sql("""
            select exists (
              select 1 from %s
              where follower_account_id = :followerId and followed_account_id = :followedId
            )
            """.formatted(table))
        .param("followerId", followerId)
        .param("followedId", followedId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public long countFollowing(String accountId) {
    return count("follower_account_id", accountId);
  }

  @Override
  public long countFollowers(String accountId) {
    return count("followed_account_id", accountId);
  }

  @Override
  public List<String> followedAccountIds(String accountId, Pageable page) {
    return page("followed_account_id", "follower_account_id", accountId, page);
  }

  @Override
  public List<String> followerAccountIds(String accountId, Pageable page) {
    return page("follower_account_id", "followed_account_id", accountId, page);
  }

  private long count(String column, String accountId) {
    return database.sql("select count(*) from %s where %s = :accountId".formatted(table, column))
        .param("accountId", accountId)
        .query(Long.class)
        .single();
  }

  private List<String> page(String selectedColumn, String filteredColumn, String accountId,
      Pageable page) {
    return database.sql("""
            select %s from %s where %s = :accountId
            order by created_on asc nulls first, account_follow_id asc
            limit :limit offset :offset
            """.formatted(selectedColumn, table, filteredColumn))
        .param("accountId", accountId)
        .param("limit", page.isPaged() ? page.getPageSize() : Integer.MAX_VALUE)
        .param("offset", page.isPaged() ? Math.toIntExact(page.getOffset()) : 0)
        .query(String.class)
        .list();
  }

  @Override
  public void deleteForAccount(String accountId) {
    database.sql("""
            delete from %s
            where follower_account_id = :accountId or followed_account_id = :accountId
            """.formatted(table))
        .param("accountId", accountId)
        .update();
  }
}

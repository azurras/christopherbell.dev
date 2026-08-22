package dev.christopherbell.post.hide;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL persistence for per-account hidden post threads. */
@PostgresPersistence
public class PostgresHiddenPostThreadRepository implements HiddenPostThreadRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresHiddenPostThreadRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("social", "hidden_post_thread");
  }

  @Override
  public HiddenPostThread save(HiddenPostThread hiddenThread) {
    return database.sql("""
            insert into %s (hidden_post_thread_id, account_id, root_post_id, created_on)
            values (:id, :accountId, :rootPostId, :createdOn)
            on conflict (account_id, root_post_id) do update set
              created_on = excluded.created_on,
              version = %s.version + 1
            returning *
            """.formatted(table, table))
        .param("id", hiddenThread.getId()).param("accountId", hiddenThread.getAccountId())
        .param("rootPostId", hiddenThread.getRootPostId())
        .param("createdOn", hiddenThread.getCreatedOn() == null
            ? null : hiddenThread.getCreatedOn().atOffset(ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .query(PostgresHiddenPostThreadRepository::map).single();
  }

  @Override
  public Optional<HiddenPostThread> findByAccountIdAndRootPostId(
      String accountId, String rootPostId) {
    return database.sql("""
            select * from %s where account_id = :accountId and root_post_id = :rootPostId
            """.formatted(table))
        .param("accountId", accountId).param("rootPostId", rootPostId)
        .query(PostgresHiddenPostThreadRepository::map).optional();
  }

  @Override
  public List<HiddenPostThread> findByAccountId(String accountId) {
    return database.sql("select * from %s where account_id = :accountId".formatted(table))
        .param("accountId", accountId).query(PostgresHiddenPostThreadRepository::map).list();
  }

  @Override
  public void deleteByAccountIdAndRootPostId(String accountId, String rootPostId) {
    database.sql("""
            delete from %s where account_id = :accountId and root_post_id = :rootPostId
            """.formatted(table))
        .param("accountId", accountId).param("rootPostId", rootPostId).update();
  }

  private static HiddenPostThread map(java.sql.ResultSet record, int rowNumber)
      throws SQLException {
    return HiddenPostThread.builder()
        .id(record.getString("hidden_post_thread_id"))
        .accountId(record.getString("account_id"))
        .rootPostId(record.getString("root_post_id"))
        .createdOn(instant(record.getObject("created_on", OffsetDateTime.class)))
        .build();
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

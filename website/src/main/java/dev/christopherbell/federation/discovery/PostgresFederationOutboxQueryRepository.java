package dev.christopherbell.federation.discovery;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL active-post queries for a local actor public outbox. */
@PostgresPersistence
public class PostgresFederationOutboxQueryRepository
    implements FederationOutboxQueryPort {
  private static final int MAX_PAGE_SIZE = 20;
  private final JdbcClient database;
  private final StableCursorCodec cursors;
  private final String table;

  public PostgresFederationOutboxQueryRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, StableCursorCodec cursors) {
    this.database = database;
    this.cursors = cursors;
    table = schemas.qualifiedTable("social", "post");
  }

  @Override
  public FederationPage<FederationOutboxEntry> page(
    String accountId, Optional<StableCursor> cursor, int requestedSize, Instant now) {
    var size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
    var boundary = cursor.orElse(null);
    var boundarySql = boundary == null ? "" : """
         and (created_on < :cursorTime or (created_on = :cursorTime and post_id < :cursorId))
        """;
    var statement = database.sql("""
            select post_id, post_text, parent_post_id, created_on, last_updated_on
            from %s
            where account_id = :accountId and federation_outbound_eligible
              and expires_on > :now and created_on is not null
            %s
            order by created_on desc, post_id desc limit :limit
            """.formatted(table, boundarySql))
        .param("accountId", accountId).param("now", now.atOffset(ZoneOffset.UTC))
        .param("limit", size + 1);
    if (boundary != null) {
      statement = statement.param("cursorTime", boundary.timestamp().atOffset(ZoneOffset.UTC))
          .param("cursorId", boundary.id());
    }
    var mapped = statement.query(PostgresFederationOutboxQueryRepository::map).list();
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
    return database.sql("""
            select count(*) from %s
            where account_id = :accountId and federation_outbound_eligible
              and expires_on > :now and created_on is not null
            """.formatted(table))
        .param("accountId", accountId).param("now", now.atOffset(ZoneOffset.UTC))
        .query(Long.class).single();
  }

  private static FederationOutboxEntry map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    var updated = row.getObject("last_updated_on", OffsetDateTime.class);
    return new FederationOutboxEntry(
        row.getString("post_id"), row.getString("post_text"), row.getString("parent_post_id"),
        row.getObject("created_on", OffsetDateTime.class).toInstant(),
        updated == null ? null : updated.toInstant());
  }
}

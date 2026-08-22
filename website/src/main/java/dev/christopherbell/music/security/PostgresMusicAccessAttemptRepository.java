package dev.christopherbell.music.security;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for atomic aggregation and bounded access-attempt queries. */
@PostgresPersistence
public class PostgresMusicAccessAttemptRepository implements MusicAccessAttemptRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresMusicAccessAttemptRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("music", "access_attempt");
  }

  @Override
  public MusicAccessAttempt record(String id, MusicAccessPrincipalType type, String principal,
      String reason, Instant occurredAt, Instant expiresAt) {
    return database.sql("""
            insert into %s
              (access_attempt_id, principal_type, principal, reason, first_attempt_at,
               last_attempt_at, attempt_count, expires_at)
            values (:id, :type, :principal, :reason, :occurredAt, :occurredAt, 1, :expiresAt)
            on conflict (access_attempt_id) do update set
              last_attempt_at = excluded.last_attempt_at,
              attempt_count = %s.attempt_count + 1,
              expires_at = excluded.expires_at
            returning *
            """.formatted(table, table))
        .param("id", id)
        .param("type", type.name())
        .param("principal", principal)
        .param("reason", reason)
        .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
        .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
        .query(PostgresMusicAccessAttemptRepository::map)
        .single();
  }

  @Override
  public List<MusicAccessAttempt> recent(int limit) {
    return database.sql("""
            select * from %s
            order by last_attempt_at desc, access_attempt_id asc
            limit :limit
            """.formatted(table))
        .param("limit", limit)
        .query(PostgresMusicAccessAttemptRepository::map)
        .list();
  }

  @Override
  public int deleteExpired(Instant cutoff, int limit) {
    return database.sql("""
            with expired as (
              select access_attempt_id from %s
              where expires_at <= :cutoff
              order by expires_at asc, access_attempt_id asc
              limit :limit
            )
            delete from %s target
            using expired
            where target.access_attempt_id = expired.access_attempt_id
              and target.expires_at <= :cutoff
            """.formatted(table, table))
        .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
        .param("limit", limit)
        .update();
  }

  private static MusicAccessAttempt map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    return new MusicAccessAttempt(
        row.getString("access_attempt_id"),
        MusicAccessPrincipalType.valueOf(row.getString("principal_type")),
        row.getString("principal"),
        row.getString("reason"),
        row.getLong("attempt_count"),
        row.getObject("first_attempt_at", OffsetDateTime.class).toInstant(),
        row.getObject("last_attempt_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant());
  }
}

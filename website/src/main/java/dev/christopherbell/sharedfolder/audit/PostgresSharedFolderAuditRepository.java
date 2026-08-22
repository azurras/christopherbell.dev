package dev.christopherbell.sharedfolder.audit;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of bounded shared-folder audit persistence. */
@PostgresPersistence
public class PostgresSharedFolderAuditRepository implements SharedFolderAuditRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresSharedFolderAuditRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("shared_folder", "audit_event");
  }

  @Override
  public SharedFolderAuditEvent save(SharedFolderAuditEvent event) {
    String id = event.id() == null ? UUID.randomUUID().toString() : event.id();
    String relativePath = event.relativePath() == null ? null
        : PostgresqlRelativePath.require(event.relativePath(), "Shared-folder audit path");
    database.sql("""
            insert into %s (
              audit_event_id, account_id, action, relative_path, size_bytes, outcome,
              failure_category, client_ip, occurred_at, expires_at)
            values (
              :id, :accountId, :action, :relativePath, :size, :outcome,
              :failureCategory, :clientIp, :occurredAt, :expiresAt)
            on conflict (audit_event_id) do update set
              account_id = excluded.account_id,
              action = excluded.action,
              relative_path = excluded.relative_path,
              size_bytes = excluded.size_bytes,
              outcome = excluded.outcome,
              failure_category = excluded.failure_category,
              client_ip = excluded.client_ip,
              occurred_at = excluded.occurred_at,
              expires_at = excluded.expires_at
            """.formatted(table))
        .paramSource(new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("accountId", event.accountId())
            .addValue("action", event.action())
            .addValue("relativePath", relativePath, Types.VARCHAR)
            .addValue("size", event.size(), Types.BIGINT)
            .addValue("outcome", event.outcome())
            .addValue("failureCategory", event.failureCategory(), Types.VARCHAR)
            .addValue("clientIp", event.clientIp(), Types.VARCHAR)
            .addValue("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
            .addValue("expiresAt", event.expiresAt().atOffset(ZoneOffset.UTC)))
        .update();
    return new SharedFolderAuditEvent(
        id, event.accountId(), event.action(), relativePath, event.size(), event.outcome(),
        event.failureCategory(), event.clientIp(), event.occurredAt(), event.expiresAt());
  }

  @Override
  public int deleteExpired(Instant cutoff, int limit) {
    return database.sql("""
            with candidates as (
              select audit_event_id from %s
              where expires_at <= :cutoff
              order by expires_at asc, audit_event_id asc
              limit :limit
              for update
            )
            delete from %s target using candidates
            where target.audit_event_id = candidates.audit_event_id
              and target.expires_at <= :cutoff
            """.formatted(table, table))
        .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
        .param("limit", limit)
        .update();
  }

  @Override
  public List<SharedFolderAuditEvent> search(
      String accountId, String action, String outcome, String relativePath,
      Instant from, Instant to, int limit) {
    var clauses = new ArrayList<String>();
    var parameters = new HashMap<String, Object>();
    if (accountId != null) {
      clauses.add("account_id = :accountId");
      parameters.put("accountId", accountId);
    }
    if (action != null) {
      clauses.add("action = :action");
      parameters.put("action", action);
    }
    if (outcome != null) {
      clauses.add("outcome = :outcome");
      parameters.put("outcome", outcome);
    }
    if (relativePath != null) {
      clauses.add("relative_path = :relativePath");
      parameters.put("relativePath", relativePath);
    }
    if (from != null) {
      clauses.add("occurred_at >= :from");
      parameters.put("from", from.atOffset(ZoneOffset.UTC));
    }
    if (to != null) {
      clauses.add("occurred_at <= :to");
      parameters.put("to", to.atOffset(ZoneOffset.UTC));
    }
    var where = clauses.isEmpty() ? "true" : String.join(" and ", clauses);
    var statement = database.sql("""
            select * from %s where %s
            order by occurred_at desc, audit_event_id desc limit :limit
            """.formatted(table, where));
    for (var entry : parameters.entrySet()) {
      statement.param(entry.getKey(), entry.getValue());
    }
    return statement.param("limit", limit)
        .query(PostgresSharedFolderAuditRepository::map)
        .list();
  }

  private static SharedFolderAuditEvent map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    return new SharedFolderAuditEvent(
        row.getString("audit_event_id"), row.getString("account_id"), row.getString("action"),
        row.getString("relative_path"), row.getObject("size_bytes", Long.class),
        row.getString("outcome"), row.getString("failure_category"), row.getString("client_ip"),
        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant());
  }
}

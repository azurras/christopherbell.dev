package dev.christopherbell.configuration.persistence;

import dev.christopherbell.libs.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStatus;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL durable scheduled-collector lifecycle store. */
@PostgresPersistence
public class PostgresScheduledCollectorRunStore implements ScheduledCollectorRunStore {
  private final JdbcClient database;
  private final String table;

  public PostgresScheduledCollectorRunStore(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("platform", "scheduled_collector_run");
  }

  @Override
  public ScheduledCollectorRun save(ScheduledCollectorRun run) {
    requireRun(run);
    return database.sql("""
            insert into %s
              (collector_run_id, collector_name, owner_token, status,
               started_on, completed_on, error_category)
            values
              (:id, :collectorName, :ownerToken, :status,
               :startedOn, :completedOn, :errorCategory)
            on conflict (collector_run_id) do update set
              collector_name = excluded.collector_name,
              owner_token = excluded.owner_token,
              status = excluded.status,
              started_on = excluded.started_on,
              completed_on = excluded.completed_on,
              error_category = excluded.error_category
            returning *
            """.formatted(table))
        .param("id", run.getId())
        .param("collectorName", run.getCollectorName())
        .param("ownerToken", run.getOwnerToken())
        .param("status", run.getStatus().name())
        .param("startedOn", run.getStartedOn().atOffset(ZoneOffset.UTC))
        .param("completedOn", offset(run.getCompletedOn()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        .param("errorCategory", run.getErrorCategory(), java.sql.Types.VARCHAR)
        .query((row, rowNumber) -> ScheduledCollectorRun.builder()
            .id(row.getString("collector_run_id"))
            .collectorName(row.getString("collector_name"))
            .ownerToken(row.getString("owner_token"))
            .status(ScheduledCollectorRunStatus.valueOf(row.getString("status")))
            .startedOn(row.getObject("started_on", java.time.OffsetDateTime.class).toInstant())
            .completedOn(instant(row.getObject(
                "completed_on", java.time.OffsetDateTime.class)))
            .errorCategory(row.getString("error_category"))
            .build())
        .single();
  }

  private static void requireRun(ScheduledCollectorRun run) {
    if (run == null || blank(run.getId()) || blank(run.getCollectorName())
        || blank(run.getOwnerToken()) || run.getStatus() == null || run.getStartedOn() == null) {
      throw new IllegalArgumentException("A collector run requires identity, owner, status, and start time.");
    }
    if (run.getCompletedOn() != null && run.getCompletedOn().isBefore(run.getStartedOn())) {
      throw new IllegalArgumentException("A collector run cannot complete before it starts.");
    }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }

  private static java.time.OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

package dev.christopherbell.configuration.persistence;

import static dev.christopherbell.persistence.jooq.platform.Tables.SCHEDULED_COLLECTOR_RUN;

import dev.christopherbell.libs.lease.ScheduledCollectorRun;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStatus;
import dev.christopherbell.libs.lease.ScheduledCollectorRunStore;
import java.time.ZoneOffset;
import org.jooq.DSLContext;

/** PostgreSQL durable scheduled-collector lifecycle store. */
@PostgresPersistence
public class PostgresScheduledCollectorRunStore implements ScheduledCollectorRunStore {
  private final DSLContext database;

  public PostgresScheduledCollectorRunStore(DSLContext database) {
    this.database = database;
  }

  @Override
  public ScheduledCollectorRun save(ScheduledCollectorRun run) {
    requireRun(run);
    database.insertInto(SCHEDULED_COLLECTOR_RUN)
        .set(SCHEDULED_COLLECTOR_RUN.COLLECTOR_RUN_ID, run.getId())
        .set(SCHEDULED_COLLECTOR_RUN.COLLECTOR_NAME, run.getCollectorName())
        .set(SCHEDULED_COLLECTOR_RUN.OWNER_TOKEN, run.getOwnerToken())
        .set(SCHEDULED_COLLECTOR_RUN.STATUS, run.getStatus().name())
        .set(SCHEDULED_COLLECTOR_RUN.STARTED_ON, run.getStartedOn().atOffset(ZoneOffset.UTC))
        .set(SCHEDULED_COLLECTOR_RUN.COMPLETED_ON, offset(run.getCompletedOn()))
        .set(SCHEDULED_COLLECTOR_RUN.ERROR_CATEGORY, run.getErrorCategory())
        .onConflict(SCHEDULED_COLLECTOR_RUN.COLLECTOR_RUN_ID).doUpdate()
        .set(SCHEDULED_COLLECTOR_RUN.COLLECTOR_NAME, run.getCollectorName())
        .set(SCHEDULED_COLLECTOR_RUN.OWNER_TOKEN, run.getOwnerToken())
        .set(SCHEDULED_COLLECTOR_RUN.STATUS, run.getStatus().name())
        .set(SCHEDULED_COLLECTOR_RUN.STARTED_ON, run.getStartedOn().atOffset(ZoneOffset.UTC))
        .set(SCHEDULED_COLLECTOR_RUN.COMPLETED_ON, offset(run.getCompletedOn()))
        .set(SCHEDULED_COLLECTOR_RUN.ERROR_CATEGORY, run.getErrorCategory())
        .execute();
    return database.selectFrom(SCHEDULED_COLLECTOR_RUN)
        .where(SCHEDULED_COLLECTOR_RUN.COLLECTOR_RUN_ID.eq(run.getId()))
        .fetchSingle(row -> ScheduledCollectorRun.builder()
            .id(row.getCollectorRunId())
            .collectorName(row.getCollectorName())
            .ownerToken(row.getOwnerToken())
            .status(ScheduledCollectorRunStatus.valueOf(row.getStatus()))
            .startedOn(row.getStartedOn().toInstant())
            .completedOn(row.getCompletedOn() == null ? null : row.getCompletedOn().toInstant())
            .errorCategory(row.getErrorCategory())
            .build());
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
}

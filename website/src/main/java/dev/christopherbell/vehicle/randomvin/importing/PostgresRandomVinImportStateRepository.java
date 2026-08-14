package dev.christopherbell.vehicle.randomvin.importing;

import static dev.christopherbell.persistence.jooq.mobility.Tables.RANDOM_VIN_IMPORT_STATE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.vehicle.randomvin.model.RandomVinImportState;
import dev.christopherbell.vehicle.randomvin.model.RandomVinRobotsPolicyState;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL RandomVIN import-state adapter. */
@PostgresPersistence
public class PostgresRandomVinImportStateRepository implements RandomVinImportStateRepository {
  private final DSLContext database;

  public PostgresRandomVinImportStateRepository(DSLContext database) { this.database = database; }

  @Override public Optional<RandomVinImportState> findById(String id) {
    return database.selectFrom(RANDOM_VIN_IMPORT_STATE)
        .where(RANDOM_VIN_IMPORT_STATE.IMPORT_STATE_ID.eq(id)).fetchOptional(row -> {
          var robots = new RandomVinRobotsPolicyState(
              instant(row.getRobotsCheckedOn()), row.getRobotsAllowed(),
              row.getRobotsReason(), row.getRobotsFailClosed());
          return RandomVinImportState.builder()
              .id(row.getImportStateId()).callsOnDate(row.getCallsOnDate()).callsToday(row.getCallsToday())
              .disabledUntil(instant(row.getDisabledUntil())).forbiddenOn(instant(row.getForbiddenOn()))
              .lastAttemptOn(instant(row.getLastAttemptOn())).lastFailureOn(instant(row.getLastFailureOn()))
              .lastFailureStatus(row.getLastFailureStatus()).lifetimeCalls(row.getLifetimeCalls())
              .lifetimeVinsProcessed(row.getLifetimeVinsProcessed()).notes(row.getNotes())
              .permanentlyDisabled(row.getPermanentlyDisabled()).robotsPolicy(robots)
              .vinsProcessedToday(row.getVinsProcessedToday()).build();
        });
  }

  @Override public RandomVinImportState save(RandomVinImportState state) {
    var robots = state.getRobotsPolicy();
    var insert = database.insertInto(RANDOM_VIN_IMPORT_STATE)
        .set(RANDOM_VIN_IMPORT_STATE.IMPORT_STATE_ID, state.getId())
        .set(RANDOM_VIN_IMPORT_STATE.CALLS_ON_DATE, state.getCallsOnDate())
        .set(RANDOM_VIN_IMPORT_STATE.CALLS_TODAY, value(state.getCallsToday()))
        .set(RANDOM_VIN_IMPORT_STATE.DISABLED_UNTIL, offset(state.getDisabledUntil()))
        .set(RANDOM_VIN_IMPORT_STATE.FORBIDDEN_ON, offset(state.getForbiddenOn()))
        .set(RANDOM_VIN_IMPORT_STATE.LAST_ATTEMPT_ON, offset(state.getLastAttemptOn()))
        .set(RANDOM_VIN_IMPORT_STATE.LAST_FAILURE_ON, offset(state.getLastFailureOn()))
        .set(RANDOM_VIN_IMPORT_STATE.LAST_FAILURE_STATUS, state.getLastFailureStatus())
        .set(RANDOM_VIN_IMPORT_STATE.LIFETIME_CALLS, value(state.getLifetimeCalls()))
        .set(RANDOM_VIN_IMPORT_STATE.LIFETIME_VINS_PROCESSED, value(state.getLifetimeVinsProcessed()))
        .set(RANDOM_VIN_IMPORT_STATE.NOTES, state.getNotes())
        .set(RANDOM_VIN_IMPORT_STATE.PERMANENTLY_DISABLED, Boolean.TRUE.equals(state.getPermanentlyDisabled()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_ALLOWED, robots != null && Boolean.TRUE.equals(robots.getAllowed()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_CHECKED_ON, robots == null ? null : offset(robots.getCheckedOn()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_FAIL_CLOSED, robots == null || !Boolean.FALSE.equals(robots.getFailClosed()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_REASON, robots == null ? null : robots.getReason())
        .set(RANDOM_VIN_IMPORT_STATE.VINS_PROCESSED_TODAY, value(state.getVinsProcessedToday()));
    insert.onConflict(RANDOM_VIN_IMPORT_STATE.IMPORT_STATE_ID).doUpdate()
        .set(RANDOM_VIN_IMPORT_STATE.CALLS_ON_DATE, state.getCallsOnDate())
        .set(RANDOM_VIN_IMPORT_STATE.CALLS_TODAY, value(state.getCallsToday()))
        .set(RANDOM_VIN_IMPORT_STATE.DISABLED_UNTIL, offset(state.getDisabledUntil()))
        .set(RANDOM_VIN_IMPORT_STATE.FORBIDDEN_ON, offset(state.getForbiddenOn()))
        .set(RANDOM_VIN_IMPORT_STATE.LAST_ATTEMPT_ON, offset(state.getLastAttemptOn()))
        .set(RANDOM_VIN_IMPORT_STATE.LAST_FAILURE_ON, offset(state.getLastFailureOn()))
        .set(RANDOM_VIN_IMPORT_STATE.LAST_FAILURE_STATUS, state.getLastFailureStatus())
        .set(RANDOM_VIN_IMPORT_STATE.LIFETIME_CALLS, value(state.getLifetimeCalls()))
        .set(RANDOM_VIN_IMPORT_STATE.LIFETIME_VINS_PROCESSED, value(state.getLifetimeVinsProcessed()))
        .set(RANDOM_VIN_IMPORT_STATE.NOTES, state.getNotes())
        .set(RANDOM_VIN_IMPORT_STATE.PERMANENTLY_DISABLED, Boolean.TRUE.equals(state.getPermanentlyDisabled()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_ALLOWED, robots != null && Boolean.TRUE.equals(robots.getAllowed()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_CHECKED_ON, robots == null ? null : offset(robots.getCheckedOn()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_FAIL_CLOSED, robots == null || !Boolean.FALSE.equals(robots.getFailClosed()))
        .set(RANDOM_VIN_IMPORT_STATE.ROBOTS_REASON, robots == null ? null : robots.getReason())
        .set(RANDOM_VIN_IMPORT_STATE.VINS_PROCESSED_TODAY, value(state.getVinsProcessedToday())).execute();
    return findById(state.getId()).orElseThrow();
  }

  private static int value(Integer value) { return value == null ? 0 : value; }
  private static long value(Long value) { return value == null ? 0 : value; }
  private static java.time.OffsetDateTime offset(java.time.Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
  private static java.time.Instant instant(java.time.OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}

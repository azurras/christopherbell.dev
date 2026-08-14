package dev.christopherbell.whatsforlunch.restaurant;

import static dev.christopherbell.persistence.jooq.lunch.Tables.RESTAURANT_IMPORT_STATE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportRunStatus;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL restaurant import checkpoint adapter. */
@PostgresPersistence
public class PostgresRestaurantImportStateRepository implements RestaurantImportStateRepository {
  private final DSLContext database;
  public PostgresRestaurantImportStateRepository(DSLContext database) { this.database = database; }
  @Override public RestaurantImportState save(RestaurantImportState state) {
    var result = state.getLastResult();
    database.insertInto(RESTAURANT_IMPORT_STATE)
        .set(RESTAURANT_IMPORT_STATE.IMPORT_STATE_ID, state.getId())
        .set(RESTAURANT_IMPORT_STATE.ACTOR_ACCOUNT_ID, state.getActorAccountId())
        .set(RESTAURANT_IMPORT_STATE.LAST_COMPLETED_MONTH, month(state.getLastCompletedMonth()))
        .set(RESTAURANT_IMPORT_STATE.LAST_COMPLETED_ON, offset(state.getLastCompletedOn()))
        .set(RESTAURANT_IMPORT_STATE.LAST_ERROR_CATEGORY, state.getLastErrorCategory())
        .set(RESTAURANT_IMPORT_STATE.LAST_FAILED_ON, offset(state.getLastFailedOn()))
        .set(RESTAURANT_IMPORT_STATE.LAST_FAILURE_MESSAGE, state.getLastFailureMessage())
        .set(RESTAURANT_IMPORT_STATE.LAST_SKIPPED_ON, offset(state.getLastSkippedOn()))
        .set(RESTAURANT_IMPORT_STATE.LAST_SKIPPED_TRIGGER, state.getLastSkippedTrigger())
        .set(RESTAURANT_IMPORT_STATE.LAST_STARTED_ON, offset(state.getLastStartedOn()))
        .set(RESTAURANT_IMPORT_STATE.RESULT_FETCHED, result == null ? null : result.fetched())
        .set(RESTAURANT_IMPORT_STATE.RESULT_IMPORTED, result == null ? null : result.imported())
        .set(RESTAURANT_IMPORT_STATE.RESULT_SKIPPED_EXISTING, result == null ? null : result.skippedExisting())
        .set(RESTAURANT_IMPORT_STATE.RESULT_SKIPPED_INVALID, result == null ? null : result.skippedInvalid())
        .set(RESTAURANT_IMPORT_STATE.RESULT_SOURCE, result == null ? null : result.source())
        .set(RESTAURANT_IMPORT_STATE.RESULT_UPDATED, result == null ? null : result.updated())
        .set(RESTAURANT_IMPORT_STATE.STATUS, state.getStatus().name())
        .set(RESTAURANT_IMPORT_STATE.TRIGGER_NAME, state.getTrigger())
        .onConflict(RESTAURANT_IMPORT_STATE.IMPORT_STATE_ID).doUpdate()
        .set(RESTAURANT_IMPORT_STATE.ACTOR_ACCOUNT_ID, state.getActorAccountId())
        .set(RESTAURANT_IMPORT_STATE.LAST_COMPLETED_MONTH, month(state.getLastCompletedMonth()))
        .set(RESTAURANT_IMPORT_STATE.LAST_COMPLETED_ON, offset(state.getLastCompletedOn()))
        .set(RESTAURANT_IMPORT_STATE.LAST_ERROR_CATEGORY, state.getLastErrorCategory())
        .set(RESTAURANT_IMPORT_STATE.LAST_FAILED_ON, offset(state.getLastFailedOn()))
        .set(RESTAURANT_IMPORT_STATE.LAST_FAILURE_MESSAGE, state.getLastFailureMessage())
        .set(RESTAURANT_IMPORT_STATE.LAST_SKIPPED_ON, offset(state.getLastSkippedOn()))
        .set(RESTAURANT_IMPORT_STATE.LAST_SKIPPED_TRIGGER, state.getLastSkippedTrigger())
        .set(RESTAURANT_IMPORT_STATE.LAST_STARTED_ON, offset(state.getLastStartedOn()))
        .set(RESTAURANT_IMPORT_STATE.RESULT_FETCHED, result == null ? null : result.fetched())
        .set(RESTAURANT_IMPORT_STATE.RESULT_IMPORTED, result == null ? null : result.imported())
        .set(RESTAURANT_IMPORT_STATE.RESULT_SKIPPED_EXISTING, result == null ? null : result.skippedExisting())
        .set(RESTAURANT_IMPORT_STATE.RESULT_SKIPPED_INVALID, result == null ? null : result.skippedInvalid())
        .set(RESTAURANT_IMPORT_STATE.RESULT_SOURCE, result == null ? null : result.source())
        .set(RESTAURANT_IMPORT_STATE.RESULT_UPDATED, result == null ? null : result.updated())
        .set(RESTAURANT_IMPORT_STATE.STATUS, state.getStatus().name())
        .set(RESTAURANT_IMPORT_STATE.TRIGGER_NAME, state.getTrigger()).execute();
    return findById(state.getId()).orElseThrow();
  }
  @Override public Optional<RestaurantImportState> findById(String id) {
    return database.selectFrom(RESTAURANT_IMPORT_STATE)
        .where(RESTAURANT_IMPORT_STATE.IMPORT_STATE_ID.eq(id)).fetchOptional(row -> {
          var result = row.getResultSource() == null ? null : RestaurantImportResult.builder()
              .source(row.getResultSource()).fetched(row.getResultFetched()).imported(row.getResultImported())
              .updated(row.getResultUpdated()).skippedExisting(row.getResultSkippedExisting())
              .skippedInvalid(row.getResultSkippedInvalid()).build();
          return RestaurantImportState.builder().id(row.getImportStateId())
              .actorAccountId(row.getActorAccountId()).lastCompletedMonth(text(row.getLastCompletedMonth()))
              .lastCompletedOn(instant(row.getLastCompletedOn())).lastErrorCategory(row.getLastErrorCategory())
              .lastFailedOn(instant(row.getLastFailedOn())).lastFailureMessage(row.getLastFailureMessage())
              .lastSkippedOn(instant(row.getLastSkippedOn())).lastSkippedTrigger(row.getLastSkippedTrigger())
              .lastStartedOn(instant(row.getLastStartedOn())).lastResult(result)
              .status(RestaurantImportRunStatus.valueOf(row.getStatus())).trigger(row.getTriggerName()).build();
        });
  }
  private static LocalDate month(String value) { return value == null ? null : YearMonth.parse(value).atDay(1); }
  private static String text(LocalDate value) { return value == null ? null : YearMonth.from(value).toString(); }
  private static java.time.OffsetDateTime offset(java.time.Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
  private static java.time.Instant instant(java.time.OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}

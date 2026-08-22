package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportRunStatus;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL restaurant import checkpoint adapter. */
@PostgresPersistence
public class PostgresRestaurantImportStateRepository implements RestaurantImportStateRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresRestaurantImportStateRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("lunch", "restaurant_import_state");
  }

  @Override
  public RestaurantImportState save(RestaurantImportState state) {
    var result = state.getLastResult();
    return database.sql("""
            insert into %s
              (import_state_id, actor_account_id, last_completed_month, last_completed_on,
               last_error_category, last_failed_on, last_failure_message, last_skipped_on,
               last_skipped_trigger, last_started_on, result_fetched, result_imported,
               result_skipped_existing, result_skipped_invalid, result_source, result_updated,
               status, trigger_name)
            values
              (:id, :actor, :month, :completedOn, :errorCategory, :failedOn, :failureMessage,
               :skippedOn, :skippedTrigger, :startedOn, :fetched, :imported, :skippedExisting,
               :skippedInvalid, :source, :updated, :status, :trigger)
            on conflict (import_state_id) do update set
              actor_account_id = excluded.actor_account_id,
              last_completed_month = excluded.last_completed_month,
              last_completed_on = excluded.last_completed_on,
              last_error_category = excluded.last_error_category,
              last_failed_on = excluded.last_failed_on,
              last_failure_message = excluded.last_failure_message,
              last_skipped_on = excluded.last_skipped_on,
              last_skipped_trigger = excluded.last_skipped_trigger,
              last_started_on = excluded.last_started_on,
              result_fetched = excluded.result_fetched,
              result_imported = excluded.result_imported,
              result_skipped_existing = excluded.result_skipped_existing,
              result_skipped_invalid = excluded.result_skipped_invalid,
              result_source = excluded.result_source,
              result_updated = excluded.result_updated,
              status = excluded.status,
              trigger_name = excluded.trigger_name
            returning *
            """.formatted(table))
        .param("id", state.getId())
        .param("actor", state.getActorAccountId(), Types.VARCHAR)
        .param("month", month(state.getLastCompletedMonth()), Types.DATE)
        .param("completedOn", offset(state.getLastCompletedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("errorCategory", state.getLastErrorCategory(), Types.VARCHAR)
        .param("failedOn", offset(state.getLastFailedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("failureMessage", state.getLastFailureMessage(), Types.VARCHAR)
        .param("skippedOn", offset(state.getLastSkippedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("skippedTrigger", state.getLastSkippedTrigger(), Types.VARCHAR)
        .param("startedOn", offset(state.getLastStartedOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("fetched", result == null ? null : result.fetched(), Types.INTEGER)
        .param("imported", result == null ? null : result.imported(), Types.INTEGER)
        .param("skippedExisting", result == null ? null : result.skippedExisting(), Types.INTEGER)
        .param("skippedInvalid", result == null ? null : result.skippedInvalid(), Types.INTEGER)
        .param("source", result == null ? null : result.source(), Types.VARCHAR)
        .param("updated", result == null ? null : result.updated(), Types.INTEGER)
        .param("status", state.getStatus().name())
        .param("trigger", state.getTrigger(), Types.VARCHAR)
        .query(PostgresRestaurantImportStateRepository::map)
        .single();
  }

  @Override
  public Optional<RestaurantImportState> findById(String id) {
    return database.sql("select * from %s where import_state_id = :id".formatted(table))
        .param("id", id)
        .query(PostgresRestaurantImportStateRepository::map)
        .optional();
  }

  private static RestaurantImportState map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    var result = row.getString("result_source") == null ? null : RestaurantImportResult.builder()
        .source(row.getString("result_source"))
        .fetched(row.getInt("result_fetched"))
        .imported(row.getInt("result_imported"))
        .updated(row.getInt("result_updated"))
        .skippedExisting(row.getInt("result_skipped_existing"))
        .skippedInvalid(row.getInt("result_skipped_invalid"))
        .build();
    return RestaurantImportState.builder()
        .id(row.getString("import_state_id"))
        .actorAccountId(row.getString("actor_account_id"))
        .lastCompletedMonth(text(row.getObject("last_completed_month", LocalDate.class)))
        .lastCompletedOn(instant(row.getObject("last_completed_on", OffsetDateTime.class)))
        .lastErrorCategory(row.getString("last_error_category"))
        .lastFailedOn(instant(row.getObject("last_failed_on", OffsetDateTime.class)))
        .lastFailureMessage(row.getString("last_failure_message"))
        .lastSkippedOn(instant(row.getObject("last_skipped_on", OffsetDateTime.class)))
        .lastSkippedTrigger(row.getString("last_skipped_trigger"))
        .lastStartedOn(instant(row.getObject("last_started_on", OffsetDateTime.class)))
        .lastResult(result)
        .status(RestaurantImportRunStatus.valueOf(row.getString("status")))
        .trigger(row.getString("trigger_name"))
        .build();
  }

  private static LocalDate month(String value) { return value == null ? null : YearMonth.parse(value).atDay(1); }
  private static String text(LocalDate value) { return value == null ? null : YearMonth.from(value).toString(); }
  private static OffsetDateTime offset(java.time.Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
  private static java.time.Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}

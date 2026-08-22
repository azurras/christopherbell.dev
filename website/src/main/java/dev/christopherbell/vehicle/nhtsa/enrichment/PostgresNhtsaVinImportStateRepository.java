package dev.christopherbell.vehicle.nhtsa.enrichment;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL NHTSA import-state adapter. */
@PostgresPersistence
public class PostgresNhtsaVinImportStateRepository implements NhtsaVinImportStateRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresNhtsaVinImportStateRepository(
      JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("mobility", "nhtsa_import_state");
  }

  @Override
  public Optional<NhtsaVinImportState> findById(String id) {
    return database.sql("select * from %s where import_state_id = :id".formatted(table))
        .param("id", id)
        .query(PostgresNhtsaVinImportStateRepository::map)
        .optional();
  }

  @Override
  public NhtsaVinImportState save(NhtsaVinImportState state) {
    return database.sql("""
            insert into %s
              (import_state_id, calls_on_date, calls_today, disabled_until, forbidden_on,
               last_attempt_on, last_failure_on, last_failure_status, lifetime_calls,
               lifetime_vins_processed, notes, permanently_disabled, vins_processed_today)
            values
              (:id, :callsOnDate, :callsToday, :disabledUntil, :forbiddenOn,
               :lastAttemptOn, :lastFailureOn, :lastFailureStatus, :lifetimeCalls,
               :lifetimeVinsProcessed, :notes, :permanentlyDisabled, :vinsProcessedToday)
            on conflict (import_state_id) do update set
              calls_on_date = excluded.calls_on_date,
              calls_today = excluded.calls_today,
              disabled_until = excluded.disabled_until,
              forbidden_on = excluded.forbidden_on,
              last_attempt_on = excluded.last_attempt_on,
              last_failure_on = excluded.last_failure_on,
              last_failure_status = excluded.last_failure_status,
              lifetime_calls = excluded.lifetime_calls,
              lifetime_vins_processed = excluded.lifetime_vins_processed,
              notes = excluded.notes,
              permanently_disabled = excluded.permanently_disabled,
              vins_processed_today = excluded.vins_processed_today
            returning *
            """.formatted(table))
        .param("id", state.getId())
        .param("callsOnDate", state.getCallsOnDate(), Types.DATE)
        .param("callsToday", state.getCallsToday(), Types.INTEGER)
        .param("disabledUntil", offset(state.getDisabledUntil()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("forbiddenOn", offset(state.getForbiddenOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("lastAttemptOn", offset(state.getLastAttemptOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("lastFailureOn", offset(state.getLastFailureOn()), Types.TIMESTAMP_WITH_TIMEZONE)
        .param("lastFailureStatus", state.getLastFailureStatus(), Types.INTEGER)
        .param("lifetimeCalls", state.getLifetimeCalls(), Types.BIGINT)
        .param("lifetimeVinsProcessed", state.getLifetimeVinsProcessed(), Types.BIGINT)
        .param("notes", state.getNotes(), Types.VARCHAR)
        .param("permanentlyDisabled", state.getPermanentlyDisabled(), Types.BOOLEAN)
        .param("vinsProcessedToday", state.getVinsProcessedToday(), Types.INTEGER)
        .query(PostgresNhtsaVinImportStateRepository::map)
        .single();
  }

  private static NhtsaVinImportState map(java.sql.ResultSet row, int rowNumber)
      throws SQLException {
    return NhtsaVinImportState.builder()
        .id(row.getString("import_state_id"))
        .callsOnDate(row.getObject("calls_on_date", java.time.LocalDate.class))
        .callsToday(row.getObject("calls_today", Integer.class))
        .disabledUntil(instant(row.getObject("disabled_until", OffsetDateTime.class)))
        .forbiddenOn(instant(row.getObject("forbidden_on", OffsetDateTime.class)))
        .lastAttemptOn(instant(row.getObject("last_attempt_on", OffsetDateTime.class)))
        .lastFailureOn(instant(row.getObject("last_failure_on", OffsetDateTime.class)))
        .lastFailureStatus(row.getObject("last_failure_status", Integer.class))
        .lifetimeCalls(row.getObject("lifetime_calls", Long.class))
        .lifetimeVinsProcessed(row.getObject("lifetime_vins_processed", Long.class))
        .notes(row.getString("notes"))
        .permanentlyDisabled(row.getObject("permanently_disabled", Boolean.class))
        .vinsProcessedToday(row.getObject("vins_processed_today", Integer.class))
        .build();
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

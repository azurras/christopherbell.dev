package dev.christopherbell.vehicle.nhtsa.enrichment;

import static dev.christopherbell.persistence.jooq.mobility.Tables.NHTSA_IMPORT_STATE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL NHTSA import-state adapter. */
@PostgresPersistence
public class PostgresNhtsaVinImportStateRepository implements NhtsaVinImportStateRepository {
  private final DSLContext database;

  public PostgresNhtsaVinImportStateRepository(DSLContext database) { this.database = database; }

  @Override public Optional<NhtsaVinImportState> findById(String id) {
    return database.selectFrom(NHTSA_IMPORT_STATE)
        .where(NHTSA_IMPORT_STATE.IMPORT_STATE_ID.eq(id)).fetchOptional(row -> NhtsaVinImportState.builder()
            .id(row.getImportStateId()).callsOnDate(row.getCallsOnDate()).callsToday(row.getCallsToday())
            .disabledUntil(instant(row.getDisabledUntil())).forbiddenOn(instant(row.getForbiddenOn()))
            .lastAttemptOn(instant(row.getLastAttemptOn())).lastFailureOn(instant(row.getLastFailureOn()))
            .lastFailureStatus(row.getLastFailureStatus()).lifetimeCalls(row.getLifetimeCalls())
            .lifetimeVinsProcessed(row.getLifetimeVinsProcessed()).notes(row.getNotes())
            .permanentlyDisabled(row.getPermanentlyDisabled())
            .vinsProcessedToday(row.getVinsProcessedToday()).build());
  }

  @Override public NhtsaVinImportState save(NhtsaVinImportState state) {
    database.insertInto(NHTSA_IMPORT_STATE)
        .set(NHTSA_IMPORT_STATE.IMPORT_STATE_ID, state.getId())
        .set(NHTSA_IMPORT_STATE.CALLS_ON_DATE, state.getCallsOnDate())
        .set(NHTSA_IMPORT_STATE.CALLS_TODAY, state.getCallsToday())
        .set(NHTSA_IMPORT_STATE.DISABLED_UNTIL, offset(state.getDisabledUntil()))
        .set(NHTSA_IMPORT_STATE.FORBIDDEN_ON, offset(state.getForbiddenOn()))
        .set(NHTSA_IMPORT_STATE.LAST_ATTEMPT_ON, offset(state.getLastAttemptOn()))
        .set(NHTSA_IMPORT_STATE.LAST_FAILURE_ON, offset(state.getLastFailureOn()))
        .set(NHTSA_IMPORT_STATE.LAST_FAILURE_STATUS, state.getLastFailureStatus())
        .set(NHTSA_IMPORT_STATE.LIFETIME_CALLS, state.getLifetimeCalls())
        .set(NHTSA_IMPORT_STATE.LIFETIME_VINS_PROCESSED, state.getLifetimeVinsProcessed())
        .set(NHTSA_IMPORT_STATE.NOTES, state.getNotes())
        .set(NHTSA_IMPORT_STATE.PERMANENTLY_DISABLED, state.getPermanentlyDisabled())
        .set(NHTSA_IMPORT_STATE.VINS_PROCESSED_TODAY, state.getVinsProcessedToday())
        .onConflict(NHTSA_IMPORT_STATE.IMPORT_STATE_ID).doUpdate()
        .set(NHTSA_IMPORT_STATE.CALLS_ON_DATE, state.getCallsOnDate())
        .set(NHTSA_IMPORT_STATE.CALLS_TODAY, state.getCallsToday())
        .set(NHTSA_IMPORT_STATE.DISABLED_UNTIL, offset(state.getDisabledUntil()))
        .set(NHTSA_IMPORT_STATE.FORBIDDEN_ON, offset(state.getForbiddenOn()))
        .set(NHTSA_IMPORT_STATE.LAST_ATTEMPT_ON, offset(state.getLastAttemptOn()))
        .set(NHTSA_IMPORT_STATE.LAST_FAILURE_ON, offset(state.getLastFailureOn()))
        .set(NHTSA_IMPORT_STATE.LAST_FAILURE_STATUS, state.getLastFailureStatus())
        .set(NHTSA_IMPORT_STATE.LIFETIME_CALLS, state.getLifetimeCalls())
        .set(NHTSA_IMPORT_STATE.LIFETIME_VINS_PROCESSED, state.getLifetimeVinsProcessed())
        .set(NHTSA_IMPORT_STATE.NOTES, state.getNotes())
        .set(NHTSA_IMPORT_STATE.PERMANENTLY_DISABLED, state.getPermanentlyDisabled())
        .set(NHTSA_IMPORT_STATE.VINS_PROCESSED_TODAY, state.getVinsProcessedToday()).execute();
    return findById(state.getId()).orElseThrow();
  }

  private static java.time.OffsetDateTime offset(java.time.Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
  private static java.time.Instant instant(java.time.OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}

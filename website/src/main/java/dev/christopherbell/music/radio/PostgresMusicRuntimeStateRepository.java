package dev.christopherbell.music.radio;

import static dev.christopherbell.persistence.jooq.music.Tables.QUEUE_ENTRY;
import static dev.christopherbell.persistence.jooq.music.Tables.RUNTIME_STATE;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.OptimisticLockingFailureException;

/** PostgreSQL adapter for independently versioned Music queue and radio state. */
@PostgresPersistence
public class PostgresMusicRuntimeStateRepository implements MusicRuntimeStateRepository {
  private final DSLContext database;

  public PostgresMusicRuntimeStateRepository(DSLContext database) {
    this.database = database;
  }

  @Override public Optional<MusicQueueState> findQueue() {
    return findQueue(database);
  }

  @Override public MusicQueueState saveQueue(MusicQueueState state) {
    return database.transactionResult(configuration -> {
      DSLContext transaction = DSL.using(configuration);
      var runtimeStateId = saveHeader(
          transaction, MusicRuntimeStateDocument.QUEUE_ID, "QUEUE", state.version(),
          null, null, null, null, null, null, null);
      transaction.deleteFrom(QUEUE_ENTRY)
          .where(QUEUE_ENTRY.RUNTIME_STATE_ID.eq(runtimeStateId)).execute();
      for (int ordinal = 0; ordinal < state.entries().size(); ordinal++) {
        MusicQueueState.Entry entry = state.entries().get(ordinal);
        transaction.insertInto(QUEUE_ENTRY)
            .set(QUEUE_ENTRY.RUNTIME_STATE_ID, runtimeStateId)
            .set(QUEUE_ENTRY.ORDINAL, ordinal).set(QUEUE_ENTRY.QUEUE_ENTRY_ID, entry.id())
            .set(QUEUE_ENTRY.TRACK_ID, entry.trackId()).set(QUEUE_ENTRY.OBSERVED_TOKEN, entry.observedToken())
            .set(QUEUE_ENTRY.ENQUEUED_BY_ACCOUNT_ID, entry.enqueuedByAccountId())
            .set(QUEUE_ENTRY.ENQUEUED_AT, entry.enqueuedAt().atOffset(ZoneOffset.UTC)).execute();
      }
      return findQueue(transaction).orElseThrow();
    });
  }

  @Override public Optional<MusicRadioState> findRadio() {
    return database.selectFrom(RUNTIME_STATE)
        .where(RUNTIME_STATE.STATE_KIND.eq("RADIO"))
        .fetchOptional(row -> new MusicRadioState(MusicRadioState.ID, row.getStationSequence(),
            row.getTrackId(), row.getObservedToken(), row.getStartedAt().toInstant(),
            row.getDurationSeconds().doubleValue(), MusicRadioState.Source.valueOf(row.getRadioSource()),
            row.getQueueEntryId(), row.getVersion()));
  }

  @Override public MusicRadioState saveRadio(MusicRadioState state) {
    saveHeader(database, MusicRuntimeStateDocument.RADIO_ID, "RADIO", state.version(),
        state.stationSequence(), state.trackId(), state.observedToken(), state.startedAt(),
        state.durationSeconds(), state.source().name(), state.queueEntryId());
    return findRadio().orElseThrow();
  }

  private static Optional<MusicQueueState> findQueue(DSLContext context) {
    return context.selectFrom(RUNTIME_STATE)
        .where(RUNTIME_STATE.STATE_KIND.eq("QUEUE"))
        .fetchOptional(row -> {
          List<MusicQueueState.Entry> entries = context.selectFrom(QUEUE_ENTRY)
              .where(QUEUE_ENTRY.RUNTIME_STATE_ID.eq(row.getRuntimeStateId()))
              .orderBy(QUEUE_ENTRY.ORDINAL.asc()).fetch(entry -> new MusicQueueState.Entry(
                  entry.getQueueEntryId(), entry.getTrackId(), entry.getObservedToken(),
                  entry.getEnqueuedByAccountId(), entry.getEnqueuedAt().toInstant()));
          return new MusicQueueState(MusicQueueState.ID, entries, row.getVersion());
        });
  }

  private static String saveHeader(
      DSLContext context, String id, String kind, Long expectedVersion,
      Long stationSequence, String trackId, String observedToken, java.time.Instant startedAt,
      Double durationSeconds, String source, String queueEntryId) {
    var existingId = context.select(RUNTIME_STATE.RUNTIME_STATE_ID).from(RUNTIME_STATE)
        .where(RUNTIME_STATE.STATE_KIND.eq(kind)).fetchOptional(RUNTIME_STATE.RUNTIME_STATE_ID);
    if (expectedVersion == null) {
      if (existingId.isPresent()) {
        throw new OptimisticLockingFailureException("Music runtime state already exists.");
      }
      context.insertInto(RUNTIME_STATE).set(RUNTIME_STATE.RUNTIME_STATE_ID, id)
          .set(RUNTIME_STATE.STATE_KIND, kind).set(RUNTIME_STATE.STATION_SEQUENCE, stationSequence)
          .set(RUNTIME_STATE.TRACK_ID, trackId).set(RUNTIME_STATE.OBSERVED_TOKEN, observedToken)
          .set(RUNTIME_STATE.STARTED_AT, startedAt == null ? null : startedAt.atOffset(ZoneOffset.UTC))
          .set(RUNTIME_STATE.DURATION_SECONDS,
              durationSeconds == null ? null : BigDecimal.valueOf(durationSeconds))
          .set(RUNTIME_STATE.RADIO_SOURCE, source).set(RUNTIME_STATE.QUEUE_ENTRY_ID, queueEntryId)
          .set(RUNTIME_STATE.VERSION, 0L).execute();
      return id;
    }
    var storedId = existingId.orElse(id);
    long nextVersion = Math.incrementExact(expectedVersion);
    int changed = context.update(RUNTIME_STATE).set(RUNTIME_STATE.STATE_KIND, kind)
        .set(RUNTIME_STATE.STATION_SEQUENCE, stationSequence).set(RUNTIME_STATE.TRACK_ID, trackId)
        .set(RUNTIME_STATE.OBSERVED_TOKEN, observedToken)
        .set(RUNTIME_STATE.STARTED_AT, startedAt == null ? null : startedAt.atOffset(ZoneOffset.UTC))
        .set(RUNTIME_STATE.DURATION_SECONDS,
            durationSeconds == null ? null : BigDecimal.valueOf(durationSeconds))
        .set(RUNTIME_STATE.RADIO_SOURCE, source).set(RUNTIME_STATE.QUEUE_ENTRY_ID, queueEntryId)
        .set(RUNTIME_STATE.VERSION, nextVersion)
        .where(RUNTIME_STATE.RUNTIME_STATE_ID.eq(storedId)
            .and(RUNTIME_STATE.VERSION.eq(expectedVersion)))
        .execute();
    if (changed != 1) {
      throw new OptimisticLockingFailureException("Music runtime state changed during save.");
    }
    return storedId;
  }
}

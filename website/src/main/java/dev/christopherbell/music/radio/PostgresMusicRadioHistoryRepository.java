package dev.christopherbell.music.radio;

import static dev.christopherbell.persistence.jooq.music.Tables.RADIO_HISTORY;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.music.tables.records.RadioHistoryRecord;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;

/** PostgreSQL implementation of bounded global Music radio history. */
@PostgresPersistence
public class PostgresMusicRadioHistoryRepository implements MusicRadioHistoryRepository {
  private final DSLContext database;

  public PostgresMusicRadioHistoryRepository(DSLContext database) {
    this.database = database;
  }

  @Override public MusicRadioHistoryEvent save(MusicRadioHistoryEvent event) {
    database.insertInto(RADIO_HISTORY).set(RADIO_HISTORY.RADIO_HISTORY_ID, event.id())
        .set(RADIO_HISTORY.STATION_SEQUENCE, event.stationSequence())
        .set(RADIO_HISTORY.TRACK_ID, event.trackId()).set(RADIO_HISTORY.OBSERVED_TOKEN, event.observedToken())
        .set(RADIO_HISTORY.ARTIST, event.artist()).set(RADIO_HISTORY.RADIO_SOURCE, event.source().name())
        .set(RADIO_HISTORY.OUTCOME, event.outcome().name())
        .set(RADIO_HISTORY.OCCURRED_AT, event.occurredAt().atOffset(ZoneOffset.UTC))
        .onConflict(RADIO_HISTORY.RADIO_HISTORY_ID).doUpdate()
        .set(RADIO_HISTORY.STATION_SEQUENCE, event.stationSequence())
        .set(RADIO_HISTORY.TRACK_ID, event.trackId()).set(RADIO_HISTORY.OBSERVED_TOKEN, event.observedToken())
        .set(RADIO_HISTORY.ARTIST, event.artist()).set(RADIO_HISTORY.RADIO_SOURCE, event.source().name())
        .set(RADIO_HISTORY.OUTCOME, event.outcome().name())
        .set(RADIO_HISTORY.OCCURRED_AT, event.occurredAt().atOffset(ZoneOffset.UTC)).execute();
    return event;
  }

  @Override public boolean existsById(String id) {
    return database.fetchExists(RADIO_HISTORY, RADIO_HISTORY.RADIO_HISTORY_ID.eq(id));
  }

  @Override public List<MusicRadioHistoryEvent> findTop100ByOrderByStationSequenceDesc() {
    return database.selectFrom(RADIO_HISTORY).orderBy(RADIO_HISTORY.STATION_SEQUENCE.desc())
        .limit(100).fetch(PostgresMusicRadioHistoryRepository::map);
  }

  private static MusicRadioHistoryEvent map(RadioHistoryRecord row) {
    return new MusicRadioHistoryEvent(row.getRadioHistoryId(), row.getStationSequence(), row.getTrackId(),
        row.getObservedToken(), row.getArtist(), MusicRadioState.Source.valueOf(row.getRadioSource()),
        MusicRadioHistoryEvent.Outcome.valueOf(row.getOutcome()), row.getOccurredAt().toInstant());
  }
}

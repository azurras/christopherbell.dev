package dev.christopherbell.music.radio;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of bounded global Music radio history. */
@PostgresPersistence
public class PostgresMusicRadioHistoryRepository implements MusicRadioHistoryRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresMusicRadioHistoryRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("music", "radio_history");
  }

  @Override public MusicRadioHistoryEvent save(MusicRadioHistoryEvent event) {
    database.sql("""
            insert into %s
              (radio_history_id, station_sequence, track_id, observed_token, artist,
               radio_source, outcome, occurred_at)
            values
              (:id, :sequence, :trackId, :token, :artist, :source, :outcome, :occurredAt)
            on conflict (radio_history_id) do update set
              station_sequence = excluded.station_sequence,
              track_id = excluded.track_id,
              observed_token = excluded.observed_token,
              artist = excluded.artist,
              radio_source = excluded.radio_source,
              outcome = excluded.outcome,
              occurred_at = excluded.occurred_at
            """.formatted(table))
        .param("id", event.id())
        .param("sequence", event.stationSequence())
        .param("trackId", event.trackId(), java.sql.Types.VARCHAR)
        .param("token", event.observedToken(), java.sql.Types.VARCHAR)
        .param("artist", event.artist(), java.sql.Types.VARCHAR)
        .param("source", event.source().name())
        .param("outcome", event.outcome().name())
        .param("occurredAt", event.occurredAt().atOffset(ZoneOffset.UTC))
        .update();
    return event;
  }

  @Override public boolean existsById(String id) {
    return database.sql("select exists(select 1 from %s where radio_history_id = :id)".formatted(
            table))
        .param("id", id)
        .query(Boolean.class)
        .single();
  }

  @Override public List<MusicRadioHistoryEvent> findTop100ByOrderByStationSequenceDesc() {
    return database.sql("select * from %s order by station_sequence desc limit 100".formatted(table))
        .query(PostgresMusicRadioHistoryRepository::map)
        .list();
  }

  private static MusicRadioHistoryEvent map(java.sql.ResultSet row, int rowNumber)
      throws java.sql.SQLException {
    return new MusicRadioHistoryEvent(
        row.getString("radio_history_id"),
        row.getLong("station_sequence"),
        row.getString("track_id"),
        row.getString("observed_token"),
        row.getString("artist"),
        MusicRadioState.Source.valueOf(row.getString("radio_source")),
        MusicRadioHistoryEvent.Outcome.valueOf(row.getString("outcome")),
        row.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant());
  }
}

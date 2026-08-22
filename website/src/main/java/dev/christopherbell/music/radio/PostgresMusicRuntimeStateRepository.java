package dev.christopherbell.music.radio;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL adapter for independently versioned Music queue and radio state. */
@PostgresPersistence
public class PostgresMusicRuntimeStateRepository implements MusicRuntimeStateRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String stateTable;
  private final String entryTable;

  public PostgresMusicRuntimeStateRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    stateTable = schemas.qualifiedTable("music", "runtime_state");
    entryTable = schemas.qualifiedTable("music", "queue_entry");
  }

  @Override
  public Optional<MusicQueueState> findQueue() {
    return database.sql("select * from %s where state_kind = 'QUEUE'".formatted(stateTable))
        .query(this::mapQueue).optional();
  }

  @Override
  public MusicQueueState saveQueue(MusicQueueState state) {
    var saved = transactions.execute(ignored -> {
      String runtimeStateId = saveHeader(
          MusicRuntimeStateDocument.QUEUE_ID, "QUEUE", state.version(),
          null, null, null, null, null, null, null);
      database.sql("delete from %s where runtime_state_id = :id".formatted(entryTable))
          .param("id", runtimeStateId).update();
      for (int ordinal = 0; ordinal < state.entries().size(); ordinal++) {
        var entry = state.entries().get(ordinal);
        database.sql("""
                insert into %s (
                  runtime_state_id, ordinal, queue_entry_id, track_id,
                  observed_token, enqueued_by_account_id, enqueued_at)
                values (:stateId, :ordinal, :entryId, :trackId, :token, :accountId, :enqueuedAt)
                """.formatted(entryTable))
            .paramSource(new MapSqlParameterSource()
                .addValue("stateId", runtimeStateId).addValue("ordinal", ordinal)
                .addValue("entryId", entry.id()).addValue("trackId", entry.trackId())
                .addValue("token", entry.observedToken(), Types.VARCHAR)
                .addValue("accountId", entry.enqueuedByAccountId(), Types.VARCHAR)
                .addValue("enqueuedAt", entry.enqueuedAt().atOffset(ZoneOffset.UTC)))
            .update();
      }
      return findQueue().orElseThrow();
    });
    if (saved == null) throw new IllegalStateException("Music queue transaction returned no value.");
    return saved;
  }

  @Override
  public Optional<MusicRadioState> findRadio() {
    return database.sql("select * from %s where state_kind = 'RADIO'".formatted(stateTable))
        .query(PostgresMusicRuntimeStateRepository::mapRadio).optional();
  }

  @Override
  public MusicRadioState saveRadio(MusicRadioState state) {
    saveHeader(MusicRuntimeStateDocument.RADIO_ID, "RADIO", state.version(),
        state.stationSequence(), state.trackId(), state.observedToken(), state.startedAt(),
        state.durationSeconds(), state.source().name(), state.queueEntryId());
    return findRadio().orElseThrow();
  }

  private String saveHeader(
      String id, String kind, Long expectedVersion, Long stationSequence, String trackId,
      String observedToken, java.time.Instant startedAt, Double durationSeconds,
      String source, String queueEntryId) {
    var existingId = database.sql("""
            select runtime_state_id from %s where state_kind = :kind
            """.formatted(stateTable)).param("kind", kind).query(String.class).optional();
    var parameters = new MapSqlParameterSource()
        .addValue("id", existingId.orElse(id)).addValue("kind", kind)
        .addValue("sequence", stationSequence, Types.BIGINT)
        .addValue("trackId", trackId, Types.VARCHAR)
        .addValue("token", observedToken, Types.VARCHAR)
        .addValue("startedAt", startedAt == null ? null : startedAt.atOffset(ZoneOffset.UTC),
            Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("duration", durationSeconds == null ? null : BigDecimal.valueOf(durationSeconds),
            Types.NUMERIC)
        .addValue("source", source, Types.VARCHAR)
        .addValue("queueEntryId", queueEntryId, Types.VARCHAR);
    if (expectedVersion == null) {
      if (existingId.isPresent()) {
        throw new OptimisticLockingFailureException("Music runtime state already exists.");
      }
      database.sql("""
              insert into %s (
                runtime_state_id, state_kind, station_sequence, track_id, observed_token,
                started_at, duration_seconds, radio_source, queue_entry_id, version)
              values (
                :id, :kind, :sequence, :trackId, :token,
                :startedAt, :duration, :source, :queueEntryId, 0)
              """.formatted(stateTable)).paramSource(parameters).update();
      return id;
    }
    int changed = database.sql("""
            update %s set station_sequence = :sequence, track_id = :trackId,
              observed_token = :token, started_at = :startedAt, duration_seconds = :duration,
              radio_source = :source, queue_entry_id = :queueEntryId, version = :nextVersion
            where runtime_state_id = :id and version = :expectedVersion
            """.formatted(stateTable))
        .paramSource(parameters
            .addValue("nextVersion", Math.incrementExact(expectedVersion))
            .addValue("expectedVersion", expectedVersion))
        .update();
    if (changed != 1) {
      throw new OptimisticLockingFailureException("Music runtime state changed during save.");
    }
    return existingId.orElse(id);
  }

  private MusicQueueState mapQueue(ResultSet row, int rowNumber) throws SQLException {
    String id = row.getString("runtime_state_id");
    List<MusicQueueState.Entry> entries = database.sql("""
            select * from %s where runtime_state_id = :id order by ordinal asc
            """.formatted(entryTable)).param("id", id).query((entry, ignored) ->
                new MusicQueueState.Entry(
                    entry.getString("queue_entry_id"), entry.getString("track_id"),
                    entry.getString("observed_token"), entry.getString("enqueued_by_account_id"),
                    entry.getObject("enqueued_at", OffsetDateTime.class).toInstant()))
        .list();
    return new MusicQueueState(MusicQueueState.ID, entries, row.getLong("version"));
  }

  private static MusicRadioState mapRadio(ResultSet row, int rowNumber) throws SQLException {
    return new MusicRadioState(MusicRadioState.ID, row.getLong("station_sequence"),
        row.getString("track_id"), row.getString("observed_token"),
        row.getObject("started_at", OffsetDateTime.class).toInstant(),
        row.getBigDecimal("duration_seconds").doubleValue(),
        MusicRadioState.Source.valueOf(row.getString("radio_source")),
        row.getString("queue_entry_id"), row.getLong("version"));
  }
}

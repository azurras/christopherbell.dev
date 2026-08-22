package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.math.BigDecimal;
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

/** PostgreSQL implementation of the one durable shared-folder radio station. */
@PostgresPersistence
public class PostgresSharedFolderRadioRepository implements SharedFolderRadioRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String stateTable;
  private final String durationTable;

  public PostgresSharedFolderRadioRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    stateTable = schemas.qualifiedTable("shared_folder", "radio_state");
    durationTable = schemas.qualifiedTable("shared_folder", "radio_track_duration");
  }

  @Override
  public Optional<SharedFolderRadioDocument> findById(String id) {
    return database.sql("select * from %s where radio_state_id = :id".formatted(stateTable))
        .param("id", id).query((row, ignored) -> map(row, durations(id))).optional();
  }

  @Override
  public SharedFolderRadioDocument save(SharedFolderRadioDocument document) {
    var saved = transactions.execute(ignored -> saveInTransaction(document));
    if (saved == null) throw new IllegalStateException("Radio transaction returned no value");
    return saved;
  }

  private SharedFolderRadioDocument saveInTransaction(SharedFolderRadioDocument document) {
    String path = document.path() == null ? null
        : PostgresqlRelativePath.require(document.path(), "Shared-folder radio path");
    var parameters = new MapSqlParameterSource()
        .addValue("id", document.id()).addValue("state", document.state().name())
        .addValue("sequence", document.stationSequence()).addValue("path", path, Types.VARCHAR)
        .addValue("startedAt", document.startedAt() == null ? null
            : document.startedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
        .addValue("duration", document.durationSeconds() == null ? null
            : BigDecimal.valueOf(document.durationSeconds()), Types.NUMERIC);
    int changed;
    if (document.version() == null) {
      changed = database.sql("""
              insert into %s (
                radio_state_id, state, station_sequence, relative_path,
                started_at, duration_seconds, version)
              values (:id, :state, :sequence, :path, :startedAt, :duration, 0)
              """.formatted(stateTable)).paramSource(parameters).update();
    } else {
      changed = database.sql("""
              update %s set state = :state, station_sequence = :sequence,
                relative_path = :path, started_at = :startedAt,
                duration_seconds = :duration, version = :nextVersion
              where radio_state_id = :id and version = :version
              """.formatted(stateTable))
          .paramSource(parameters.addValue("version", document.version())
              .addValue("nextVersion", Math.incrementExact(document.version())))
          .update();
    }
    if (changed != 1) {
      throw new OptimisticLockingFailureException("Shared-folder radio changed during save.");
    }
    database.sql("delete from %s where radio_state_id = :id".formatted(durationTable))
        .param("id", document.id()).update();
    for (int ordinal = 0; ordinal < document.knownDurations().size(); ordinal++) {
      var duration = document.knownDurations().get(ordinal);
      database.sql("""
              insert into %s (
                radio_state_id, ordinal, relative_path, observed_token, duration_seconds)
              values (:id, :ordinal, :path, :token, :duration)
              """.formatted(durationTable))
          .param("id", document.id()).param("ordinal", ordinal)
          .param("path", PostgresqlRelativePath.require(
              duration.path(), "Shared-folder duration path"))
          .param("token", duration.observedToken())
          .param("duration", BigDecimal.valueOf(duration.durationSeconds())).update();
    }
    return findById(document.id()).orElseThrow();
  }

  private List<SharedFolderRadioDocument.TrackDuration> durations(String id) {
    return database.sql("""
            select * from %s where radio_state_id = :id order by ordinal asc
            """.formatted(durationTable))
        .param("id", id)
        .query((row, ignored) -> new SharedFolderRadioDocument.TrackDuration(
            row.getString("relative_path"), row.getString("observed_token"),
            row.getBigDecimal("duration_seconds").doubleValue()))
        .list();
  }

  private static SharedFolderRadioDocument map(
      java.sql.ResultSet row, List<SharedFolderRadioDocument.TrackDuration> durations)
      throws SQLException {
    var started = row.getObject("started_at", OffsetDateTime.class);
    var duration = row.getBigDecimal("duration_seconds");
    return new SharedFolderRadioDocument(
        row.getString("radio_state_id"),
        SharedFolderRadioDocument.State.valueOf(row.getString("state")),
        row.getLong("station_sequence"), row.getString("relative_path"),
        started == null ? null : started.toInstant(),
        duration == null ? null : duration.doubleValue(), durations, row.getLong("version"));
  }
}

package dev.christopherbell.music.catalog;

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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL implementation of indexed Music track persistence. */
@PostgresPersistence
public class PostgresMusicTrackRepository implements MusicTrackRepository {
  private final JdbcClient database;
  private final String table;

  public PostgresMusicTrackRepository(JdbcClient database, PostgresqlSchemaNames schemas) {
    this.database = database;
    table = schemas.qualifiedTable("music", "track");
  }

  @Override
  public MusicTrack save(MusicTrack track) {
    String path = PostgresqlRelativePath.require(track.path(), "Music track path");
    database.sql("""
            insert into %s (
              track_id, relative_path, observed_token, pending_observed_token, title,
              artist, album_artist, album, track_number, disc_number, genre,
              release_year, duration_seconds, audio_codec, container, artwork_revision,
              favorite, excluded_from_radio, index_status, index_failure,
              last_probe_attempt_at, indexed_at, missing_since)
            values (
              :id, :path, :observedToken, :pendingToken, :title,
              :artist, :albumArtist, :album, :trackNumber, :discNumber, :genre,
              :year, :duration, :audioCodec, :container, :artworkRevision,
              :favorite, :excluded, :status, :failure,
              :lastProbe, :indexedAt, :missingSince)
            on conflict (track_id) do update set
              relative_path = excluded.relative_path,
              observed_token = excluded.observed_token,
              pending_observed_token = excluded.pending_observed_token,
              title = excluded.title,
              artist = excluded.artist,
              album_artist = excluded.album_artist,
              album = excluded.album,
              track_number = excluded.track_number,
              disc_number = excluded.disc_number,
              genre = excluded.genre,
              release_year = excluded.release_year,
              duration_seconds = excluded.duration_seconds,
              audio_codec = excluded.audio_codec,
              container = excluded.container,
              artwork_revision = excluded.artwork_revision,
              favorite = excluded.favorite,
              excluded_from_radio = excluded.excluded_from_radio,
              index_status = excluded.index_status,
              index_failure = excluded.index_failure,
              last_probe_attempt_at = excluded.last_probe_attempt_at,
              indexed_at = excluded.indexed_at,
              missing_since = excluded.missing_since
            """.formatted(table))
        .paramSource(new MapSqlParameterSource()
            .addValue("id", track.id()).addValue("path", path)
            .addValue("observedToken", track.observedToken())
            .addValue("pendingToken", track.pendingObservedToken(), Types.VARCHAR)
            .addValue("title", track.title()).addValue("artist", track.artist(), Types.VARCHAR)
            .addValue("albumArtist", track.albumArtist(), Types.VARCHAR)
            .addValue("album", track.album(), Types.VARCHAR)
            .addValue("trackNumber", track.trackNumber(), Types.INTEGER)
            .addValue("discNumber", track.discNumber(), Types.INTEGER)
            .addValue("genre", track.genre(), Types.VARCHAR)
            .addValue("year", track.year(), Types.INTEGER)
            .addValue("duration", BigDecimal.valueOf(track.durationSeconds()))
            .addValue("audioCodec", track.audioCodec(), Types.VARCHAR)
            .addValue("container", track.container(), Types.VARCHAR)
            .addValue("artworkRevision", track.artworkRevision(), Types.VARCHAR)
            .addValue("favorite", track.favorite()).addValue("excluded", track.excludedFromRadio())
            .addValue("status", track.indexStatus().name())
            .addValue("failure", track.indexFailure(), Types.VARCHAR)
            .addValue("lastProbe", offset(track.lastProbeAttemptAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("indexedAt", offset(track.indexedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("missingSince", offset(track.missingSince()), Types.TIMESTAMP_WITH_TIMEZONE))
        .update();
    return findById(track.id()).orElseThrow();
  }

  @Override
  public Optional<MusicTrack> findById(String id) {
    return database.sql("select * from %s where track_id = :id".formatted(table))
        .param("id", id).query(PostgresMusicTrackRepository::map).optional();
  }

  @Override
  public Optional<MusicTrack> findByPath(String path) {
    return database.sql("select * from %s where relative_path = :path".formatted(table))
        .param("path", path).query(PostgresMusicTrackRepository::map).optional();
  }

  @Override
  public List<MusicTrack> findAllByMissingSinceIsNull() {
    return database.sql("select * from %s where missing_since is null".formatted(table))
        .query(PostgresMusicTrackRepository::map).list();
  }

  @Override
  public boolean updatePreferences(
      String id, boolean expectedFavorite, boolean expectedExcluded,
      boolean favorite, boolean excluded) {
    return database.sql("""
            update %s set favorite = :favorite, excluded_from_radio = :excluded
            where track_id = :id and favorite = :expectedFavorite
              and excluded_from_radio = :expectedExcluded
            """.formatted(table))
        .param("favorite", favorite).param("excluded", excluded).param("id", id)
        .param("expectedFavorite", expectedFavorite)
        .param("expectedExcluded", expectedExcluded).update() == 1;
  }

  static MusicTrack map(java.sql.ResultSet row, int rowNumber) throws SQLException {
    return new MusicTrack(
        row.getString("track_id"), row.getString("relative_path"),
        row.getString("observed_token"), row.getString("pending_observed_token"),
        row.getString("title"), row.getString("artist"), row.getString("album_artist"),
        row.getString("album"), row.getObject("track_number", Integer.class),
        row.getObject("disc_number", Integer.class), row.getString("genre"),
        row.getObject("release_year", Integer.class), row.getBigDecimal("duration_seconds").doubleValue(),
        row.getString("audio_codec"), row.getString("container"), row.getString("artwork_revision"),
        row.getBoolean("favorite"), row.getBoolean("excluded_from_radio"),
        MusicIndexStatus.valueOf(row.getString("index_status")), row.getString("index_failure"),
        instant(row.getObject("last_probe_attempt_at", OffsetDateTime.class)),
        instant(row.getObject("indexed_at", OffsetDateTime.class)),
        instant(row.getObject("missing_since", OffsetDateTime.class)));
  }

  private static OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

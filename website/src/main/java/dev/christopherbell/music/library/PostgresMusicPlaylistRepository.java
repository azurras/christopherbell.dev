package dev.christopherbell.music.library;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlIntegrityViolationTranslator;
import dev.christopherbell.configuration.persistence.PostgresqlSchemaNames;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** PostgreSQL implementation of global optimistic Music playlists. */
@PostgresPersistence
public class PostgresMusicPlaylistRepository implements MusicPlaylistRepository {
  private final JdbcClient database;
  private final TransactionOperations transactions;
  private final String playlistTable;
  private final String trackTable;

  public PostgresMusicPlaylistRepository(
      JdbcClient database, PostgresqlSchemaNames schemas, TransactionOperations transactions) {
    this.database = database;
    this.transactions = transactions;
    playlistTable = schemas.qualifiedTable("music", "playlist");
    trackTable = schemas.qualifiedTable("music", "playlist_track");
  }

  @Override
  public MusicPlaylist save(MusicPlaylist playlist) {
    try {
      var saved = transactions.execute(ignored -> {
        if (playlist.version() == null) {
          database.sql("""
                  insert into %s (
                    playlist_id, normalized_name, name, version,
                    updated_by_account_id, updated_at)
                  values (:id, :normalizedName, :name, 0, :accountId, :updatedAt)
                  """.formatted(playlistTable))
              .param("id", playlist.id()).param("normalizedName", playlist.normalizedName())
              .param("name", playlist.name()).param("accountId", playlist.updatedByAccountId())
              .param("updatedAt", playlist.updatedAt().atOffset(ZoneOffset.UTC)).update();
        } else {
          int changed = database.sql("""
                  update %s set normalized_name = :normalizedName, name = :name,
                    version = :nextVersion, updated_by_account_id = :accountId,
                    updated_at = :updatedAt
                  where playlist_id = :id and version = :expectedVersion
                  """.formatted(playlistTable))
              .param("id", playlist.id()).param("normalizedName", playlist.normalizedName())
              .param("name", playlist.name())
              .param("nextVersion", Math.incrementExact(playlist.version()))
              .param("expectedVersion", playlist.version())
              .param("accountId", playlist.updatedByAccountId())
              .param("updatedAt", playlist.updatedAt().atOffset(ZoneOffset.UTC)).update();
          if (changed != 1) {
            throw new OptimisticLockingFailureException("Music playlist changed during save.");
          }
        }
        database.sql("delete from %s where playlist_id = :id".formatted(trackTable))
            .param("id", playlist.id()).update();
        for (int ordinal = 0; ordinal < playlist.trackIds().size(); ordinal++) {
          database.sql("""
                  insert into %s (playlist_id, ordinal, track_id)
                  values (:id, :ordinal, :trackId)
                  """.formatted(trackTable))
              .param("id", playlist.id()).param("ordinal", ordinal)
              .param("trackId", playlist.trackIds().get(ordinal)).update();
        }
        return findById(playlist.id()).orElseThrow();
      });
      if (saved == null) {
        throw new IllegalStateException("Music playlist transaction returned no value.");
      }
      return saved;
    } catch (DataIntegrityViolationException failure) {
      throw PostgresqlIntegrityViolationTranslator.translate(
          sqlState(failure),
          "PostgreSQL rejected a duplicate music playlist identity.",
          "PostgreSQL rejected music playlist data.");
    }
  }

  private static String sqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlFailure) {
        return sqlFailure.getSQLState();
      }
    }
    return null;
  }

  @Override
  public Optional<MusicPlaylist> findById(String id) {
    return database.sql("select * from %s where playlist_id = :id".formatted(playlistTable))
        .param("id", id).query(this::map).optional();
  }

  @Override
  public List<MusicPlaylist> findTop100ByOrderByNormalizedNameAsc() {
    return database.sql("""
            select * from %s order by normalized_name asc, playlist_id asc limit 100
            """.formatted(playlistTable)).query(this::map).list();
  }

  @Override
  public long count() {
    return database.sql("select count(*) from %s".formatted(playlistTable))
        .query(Long.class).single();
  }

  @Override
  public void delete(MusicPlaylist playlist) {
    var statement = playlist.version() == null
        ? database.sql("delete from %s where playlist_id = :id".formatted(playlistTable))
            .param("id", playlist.id())
        : database.sql("delete from %s where playlist_id = :id and version = :version"
                .formatted(playlistTable))
            .param("id", playlist.id()).param("version", playlist.version());
    if (statement.update() != 1) {
      throw new OptimisticLockingFailureException("Music playlist changed during deletion.");
    }
  }

  private MusicPlaylist map(ResultSet row, int rowNumber) throws SQLException {
    String id = row.getString("playlist_id");
    var tracks = database.sql("""
            select track_id from %s where playlist_id = :id order by ordinal asc
            """.formatted(trackTable)).param("id", id).query(String.class).list();
    return new MusicPlaylist(id, row.getString("normalized_name"), row.getString("name"), tracks,
        row.getLong("version"), row.getString("updated_by_account_id"),
        row.getObject("updated_at", OffsetDateTime.class).toInstant());
  }
}

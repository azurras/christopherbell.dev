package dev.christopherbell.music.library;

import static dev.christopherbell.persistence.jooq.music.Tables.PLAYLIST;
import static dev.christopherbell.persistence.jooq.music.Tables.PLAYLIST_TRACK;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.persistence.jooq.music.tables.records.PlaylistRecord;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

/** PostgreSQL implementation of global optimistic Music playlists. */
@PostgresPersistence
public class PostgresMusicPlaylistRepository implements MusicPlaylistRepository {
  private final DSLContext database;

  public PostgresMusicPlaylistRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public MusicPlaylist save(MusicPlaylist playlist) {
    try {
      return database.transactionResult(configuration -> {
        DSLContext transaction = DSL.using(configuration);
        long nextVersion;
        if (playlist.version() == null) {
          transaction.insertInto(PLAYLIST).set(PLAYLIST.PLAYLIST_ID, playlist.id())
              .set(PLAYLIST.NORMALIZED_NAME, playlist.normalizedName())
              .set(PLAYLIST.NAME, playlist.name()).set(PLAYLIST.VERSION, 0L)
              .set(PLAYLIST.UPDATED_BY_ACCOUNT_ID, playlist.updatedByAccountId())
              .set(PLAYLIST.UPDATED_AT, playlist.updatedAt().atOffset(ZoneOffset.UTC)).execute();
          nextVersion = 0;
        } else {
          nextVersion = Math.incrementExact(playlist.version());
          int changed = transaction.update(PLAYLIST)
              .set(PLAYLIST.NORMALIZED_NAME, playlist.normalizedName())
              .set(PLAYLIST.NAME, playlist.name()).set(PLAYLIST.VERSION, nextVersion)
              .set(PLAYLIST.UPDATED_BY_ACCOUNT_ID, playlist.updatedByAccountId())
              .set(PLAYLIST.UPDATED_AT, playlist.updatedAt().atOffset(ZoneOffset.UTC))
              .where(PLAYLIST.PLAYLIST_ID.eq(playlist.id())
                  .and(PLAYLIST.VERSION.eq(playlist.version())))
              .execute();
          if (changed != 1) {
            throw new OptimisticLockingFailureException("Music playlist changed during save.");
          }
        }
        transaction.deleteFrom(PLAYLIST_TRACK)
            .where(PLAYLIST_TRACK.PLAYLIST_ID.eq(playlist.id())).execute();
        for (int ordinal = 0; ordinal < playlist.trackIds().size(); ordinal++) {
          transaction.insertInto(PLAYLIST_TRACK).set(PLAYLIST_TRACK.PLAYLIST_ID, playlist.id())
              .set(PLAYLIST_TRACK.ORDINAL, ordinal)
              .set(PLAYLIST_TRACK.TRACK_ID, playlist.trackIds().get(ordinal)).execute();
        }
        return requirePlaylist(transaction, playlist.id());
      });
    } catch (org.jooq.exception.IntegrityConstraintViolationException failure) {
      if ("23505".equals(failure.sqlState())) {
        throw new DuplicateKeyException("PostgreSQL rejected a duplicate Music playlist.", failure);
      }
      throw new DataIntegrityViolationException(
          "PostgreSQL rejected a Music playlist relationship.", failure);
    }
  }

  @Override public Optional<MusicPlaylist> findById(String id) {
    return find(database, id);
  }

  @Override public List<MusicPlaylist> findTop100ByOrderByNormalizedNameAsc() {
    return database.selectFrom(PLAYLIST).orderBy(PLAYLIST.NORMALIZED_NAME.asc(), PLAYLIST.PLAYLIST_ID.asc())
        .limit(100).fetch(row -> map(database, row));
  }

  @Override public long count() {
    return database.fetchCount(PLAYLIST);
  }

  @Override public void delete(MusicPlaylist playlist) {
    var condition = PLAYLIST.PLAYLIST_ID.eq(playlist.id());
    if (playlist.version() != null) condition = condition.and(PLAYLIST.VERSION.eq(playlist.version()));
    if (database.deleteFrom(PLAYLIST).where(condition).execute() != 1) {
      throw new OptimisticLockingFailureException("Music playlist changed during deletion.");
    }
  }

  private static Optional<MusicPlaylist> find(DSLContext context, String id) {
    return context.selectFrom(PLAYLIST).where(PLAYLIST.PLAYLIST_ID.eq(id))
        .fetchOptional(row -> map(context, row));
  }

  private static MusicPlaylist requirePlaylist(DSLContext context, String id) {
    return find(context, id).orElseThrow();
  }

  private static MusicPlaylist map(DSLContext context, PlaylistRecord row) {
    List<String> trackIds = context.select(PLAYLIST_TRACK.TRACK_ID).from(PLAYLIST_TRACK)
        .where(PLAYLIST_TRACK.PLAYLIST_ID.eq(row.getPlaylistId()))
        .orderBy(PLAYLIST_TRACK.ORDINAL.asc()).fetch(PLAYLIST_TRACK.TRACK_ID);
    return new MusicPlaylist(row.getPlaylistId(), row.getNormalizedName(), row.getName(), trackIds,
        row.getVersion(), row.getUpdatedByAccountId(), row.getUpdatedAt().toInstant());
  }
}

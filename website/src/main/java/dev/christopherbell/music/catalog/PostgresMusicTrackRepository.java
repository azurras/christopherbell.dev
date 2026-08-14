package dev.christopherbell.music.catalog;

import static dev.christopherbell.persistence.jooq.music.Tables.TRACK;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import dev.christopherbell.configuration.persistence.PostgresqlRelativePath;
import dev.christopherbell.persistence.jooq.music.tables.records.TrackRecord;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;

/** PostgreSQL implementation of indexed Music track persistence. */
@PostgresPersistence
public class PostgresMusicTrackRepository implements MusicTrackRepository {
  private final DSLContext database;

  public PostgresMusicTrackRepository(DSLContext database) {
    this.database = database;
  }

  @Override
  public MusicTrack save(MusicTrack track) {
    String path = PostgresqlRelativePath.require(track.path(), "Music track path");
    database.insertInto(TRACK)
        .set(TRACK.TRACK_ID, track.id()).set(TRACK.RELATIVE_PATH, path)
        .set(TRACK.OBSERVED_TOKEN, track.observedToken())
        .set(TRACK.PENDING_OBSERVED_TOKEN, track.pendingObservedToken())
        .set(TRACK.TITLE, track.title()).set(TRACK.ARTIST, track.artist())
        .set(TRACK.ALBUM_ARTIST, track.albumArtist()).set(TRACK.ALBUM, track.album())
        .set(TRACK.TRACK_NUMBER, track.trackNumber()).set(TRACK.DISC_NUMBER, track.discNumber())
        .set(TRACK.GENRE, track.genre()).set(TRACK.RELEASE_YEAR, track.year())
        .set(TRACK.DURATION_SECONDS, BigDecimal.valueOf(track.durationSeconds()))
        .set(TRACK.AUDIO_CODEC, track.audioCodec()).set(TRACK.CONTAINER, track.container())
        .set(TRACK.ARTWORK_REVISION, track.artworkRevision()).set(TRACK.FAVORITE, track.favorite())
        .set(TRACK.EXCLUDED_FROM_RADIO, track.excludedFromRadio())
        .set(TRACK.INDEX_STATUS, track.indexStatus().name()).set(TRACK.INDEX_FAILURE, track.indexFailure())
        .set(TRACK.LAST_PROBE_ATTEMPT_AT, offset(track.lastProbeAttemptAt()))
        .set(TRACK.INDEXED_AT, offset(track.indexedAt())).set(TRACK.MISSING_SINCE, offset(track.missingSince()))
        .onConflict(TRACK.TRACK_ID).doUpdate()
        .set(TRACK.RELATIVE_PATH, path).set(TRACK.OBSERVED_TOKEN, track.observedToken())
        .set(TRACK.PENDING_OBSERVED_TOKEN, track.pendingObservedToken())
        .set(TRACK.TITLE, track.title()).set(TRACK.ARTIST, track.artist())
        .set(TRACK.ALBUM_ARTIST, track.albumArtist()).set(TRACK.ALBUM, track.album())
        .set(TRACK.TRACK_NUMBER, track.trackNumber()).set(TRACK.DISC_NUMBER, track.discNumber())
        .set(TRACK.GENRE, track.genre()).set(TRACK.RELEASE_YEAR, track.year())
        .set(TRACK.DURATION_SECONDS, BigDecimal.valueOf(track.durationSeconds()))
        .set(TRACK.AUDIO_CODEC, track.audioCodec()).set(TRACK.CONTAINER, track.container())
        .set(TRACK.ARTWORK_REVISION, track.artworkRevision()).set(TRACK.FAVORITE, track.favorite())
        .set(TRACK.EXCLUDED_FROM_RADIO, track.excludedFromRadio())
        .set(TRACK.INDEX_STATUS, track.indexStatus().name()).set(TRACK.INDEX_FAILURE, track.indexFailure())
        .set(TRACK.LAST_PROBE_ATTEMPT_AT, offset(track.lastProbeAttemptAt()))
        .set(TRACK.INDEXED_AT, offset(track.indexedAt())).set(TRACK.MISSING_SINCE, offset(track.missingSince()))
        .execute();
    return findById(track.id()).orElseThrow();
  }

  @Override public Optional<MusicTrack> findById(String id) {
    return database.selectFrom(TRACK).where(TRACK.TRACK_ID.eq(id)).fetchOptional(PostgresMusicTrackRepository::map);
  }

  @Override public Optional<MusicTrack> findByPath(String path) {
    return database.selectFrom(TRACK).where(TRACK.RELATIVE_PATH.eq(path)).fetchOptional(PostgresMusicTrackRepository::map);
  }

  @Override public List<MusicTrack> findAllByMissingSinceIsNull() {
    return database.selectFrom(TRACK).where(TRACK.MISSING_SINCE.isNull()).fetch(PostgresMusicTrackRepository::map);
  }

  @Override
  public boolean updatePreferences(String id, boolean expectedFavorite, boolean expectedExcluded,
      boolean favorite, boolean excluded) {
    return database.update(TRACK).set(TRACK.FAVORITE, favorite).set(TRACK.EXCLUDED_FROM_RADIO, excluded)
        .where(TRACK.TRACK_ID.eq(id).and(TRACK.FAVORITE.eq(expectedFavorite))
            .and(TRACK.EXCLUDED_FROM_RADIO.eq(expectedExcluded))).execute() == 1;
  }

  static MusicTrack map(TrackRecord row) {
    return new MusicTrack(row.getTrackId(), row.getRelativePath(), row.getObservedToken(),
        row.getPendingObservedToken(), row.getTitle(), row.getArtist(), row.getAlbumArtist(),
        row.getAlbum(), row.getTrackNumber(), row.getDiscNumber(), row.getGenre(), row.getReleaseYear(),
        row.getDurationSeconds().doubleValue(), row.getAudioCodec(), row.getContainer(),
        row.getArtworkRevision(), row.getFavorite(), row.getExcludedFromRadio(),
        MusicIndexStatus.valueOf(row.getIndexStatus()), row.getIndexFailure(),
        instant(row.getLastProbeAttemptAt()), instant(row.getIndexedAt()), instant(row.getMissingSince()));
  }

  private static java.time.OffsetDateTime offset(java.time.Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  private static java.time.Instant instant(java.time.OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }
}

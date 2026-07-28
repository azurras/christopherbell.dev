package dev.christopherbell.music.catalog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** Durable metadata for one file below the configured Music root. */
@Document("music_tracks")
public record MusicTrack(
    @Id String id,
    @Indexed(unique = true) String path,
    String observedToken,
    String pendingObservedToken,
    String title,
    @Indexed String artist,
    String albumArtist,
    @Indexed String album,
    Integer trackNumber,
    Integer discNumber,
    @Indexed String genre,
    Integer year,
    double durationSeconds,
    String audioCodec,
    String container,
    String artworkRevision,
    boolean favorite,
    boolean excludedFromRadio,
    MusicIndexStatus indexStatus,
    String indexFailure,
    Instant lastProbeAttemptAt,
    Instant indexedAt,
    Instant missingSince) {

  public static MusicTrack ready(
      String path,
      String observedToken,
      MusicProbeResult metadata,
      String artworkRevision,
      Instant now) {
    return indexed(null, path, observedToken, metadata, artworkRevision, now);
  }

  public static MusicTrack indexed(
      MusicTrack previous,
      String path,
      String observedToken,
      MusicProbeResult metadata,
      String artworkRevision,
      Instant now) {
    return new MusicTrack(
        id(path), path, observedToken, null,
        title(metadata.title(), path), metadata.artist(), metadata.albumArtist(), metadata.album(),
        metadata.trackNumber(), metadata.discNumber(), metadata.genre(), metadata.year(),
        metadata.durationSeconds(), metadata.audioCodec(), metadata.container(), artworkRevision,
        previous != null && previous.favorite(),
        previous != null && previous.excludedFromRadio(),
        MusicIndexStatus.READY, null, now, now, null);
  }

  public static MusicTrack probeFailed(
      MusicTrack previous,
      String path,
      String pendingObservedToken,
      String failure,
      Instant now) {
    if (previous == null) {
      return new MusicTrack(
          id(path), path, null, pendingObservedToken, title(null, path), null, null, null,
          null, null, null, null, 0, null, null, null, false, false,
          MusicIndexStatus.PROBE_FAILED, failure, now, null, null);
    }
    return new MusicTrack(
        previous.id(), previous.path(), previous.observedToken(), pendingObservedToken,
        previous.title(), previous.artist(), previous.albumArtist(), previous.album(),
        previous.trackNumber(), previous.discNumber(), previous.genre(), previous.year(),
        previous.durationSeconds(), previous.audioCodec(), previous.container(),
        previous.artworkRevision(), previous.favorite(), previous.excludedFromRadio(),
        MusicIndexStatus.PROBE_FAILED, failure, now, previous.indexedAt(), null);
  }

  public MusicTrack markMissing(Instant now) {
    if (missingSince != null) return this;
    return new MusicTrack(
        id, path, observedToken, pendingObservedToken, title, artist, albumArtist, album,
        trackNumber, discNumber, genre, year, durationSeconds, audioCodec, container,
        artworkRevision, favorite, excludedFromRadio, indexStatus, indexFailure,
        lastProbeAttemptAt, indexedAt, now);
  }

  public boolean playable(String currentObservedToken) {
    return missingSince == null
        && indexStatus == MusicIndexStatus.READY
        && observedToken != null
        && observedToken.equals(currentObservedToken);
  }

  private static String id(String path) {
    return UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static String title(String metadataTitle, String path) {
    if (metadataTitle != null && !metadataTitle.isBlank()) return metadataTitle;
    String name = path.substring(path.lastIndexOf('/') + 1);
    int extension = name.lastIndexOf('.');
    return extension > 0 ? name.substring(0, extension) : name;
  }
}

package dev.christopherbell.music.metadata;

import dev.christopherbell.libs.lease.LeaseOwnershipLostException;
import dev.christopherbell.libs.lease.LeaseService;
import dev.christopherbell.libs.lease.ScheduledCollectorCoordinator;
import dev.christopherbell.music.catalog.MusicArtworkService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicFileRevision;
import dev.christopherbell.music.catalog.MusicProbe;
import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.music.security.MusicAccessService;
import dev.christopherbell.music.web.MusicTrackView;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;

/** Coordinates fail-closed, revision-checked Music tag edits and checksum-bound undo. */
public final class MusicMetadataService {
  private static final Set<String> WRITABLE_EXTENSIONS = Set.of("mp3", "flac", "m4a");
  private static final double DURATION_TOLERANCE_SECONDS = 0.5;
  private static final Duration CLEANUP_LEASE_DURATION = Duration.ofMinutes(10);
  private final MusicProperties music;
  private final MusicMetadataProperties properties;
  private final MusicCatalog catalog;
  private final MusicTrackRepository tracks;
  private final MusicProbe probe;
  private final MusicArtworkService artwork;
  private final MusicTagProcess tagProcess;
  private final MusicMetadataFileStore files;
  private final MusicMetadataEditRepository edits;
  private final MusicAccessService access;
  private final LeaseService leases;
  private final ScheduledCollectorCoordinator scheduledCollectors;
  private final Clock clock;
  private final Object localLock = new Object();

  public MusicMetadataService(
      MusicProperties music,
      MusicMetadataProperties properties,
      MusicCatalog catalog,
      MusicTrackRepository tracks,
      MusicProbe probe,
      MusicArtworkService artwork,
      MusicTagProcess tagProcess,
      MusicMetadataFileStore files,
      MusicMetadataEditRepository edits,
      MusicAccessService access,
      LeaseService leases,
      ScheduledCollectorCoordinator scheduledCollectors,
      Clock clock) {
    this.music = music;
    this.properties = properties;
    this.catalog = catalog;
    this.tracks = tracks;
    this.probe = probe;
    this.artwork = artwork;
    this.tagProcess = tagProcess;
    this.files = files;
    this.edits = edits;
    this.access = access;
    this.leases = leases;
    this.scheduledCollectors = scheduledCollectors;
    this.clock = clock;
  }

  public MusicMetadataResult edit(String trackId, MusicMetadataUpdate rawUpdate) {
    var account = access.requireWrite();
    requireIdentifier(trackId);
    MusicMetadataUpdate update = validate(rawUpdate);
    return locked(trackId, () -> {
      MusicTrack current = ready(trackId);
      requireRevision(current.observedToken(), update.expectedObservedToken());
      String extension = extension(current.path());
      Path source = source(current.path());
      requireDiskRevision(source, update.expectedObservedToken());
      long originalModifiedMillis = observe(source).modifiedMillis();
      byte[] artworkBytes = artwork(update.artworkDataUrl(), update.removeArtwork());
      String editId = UUID.randomUUID().toString();
      MusicMetadataFileStore.Prepared prepared = files.prepare(
          source, editId, extension, artworkBytes);
      boolean applied = false;
      MusicMetadataEdit preparedEdit = null;
      try {
        requireDiskRevision(source, update.expectedObservedToken());
        tagProcess.rewrite(source, prepared.stage(), update, prepared.artwork());
        files.validateStage(prepared);
        MusicProbeResult rewritten = probe.probe(prepared.stage().toAbsolutePath().normalize());
        requirePreserved(current, rewritten);
        requireDiskRevision(source, update.expectedObservedToken());
        preparedEdit = new MusicMetadataEdit(
            editId, current.id(), current.path(), prepared.backupFileName(),
            prepared.backupSha256(), current.observedToken(), null, current.audioCodec(),
            current.durationSeconds(), account.getId(), clock.instant(),
            clock.instant().plus(properties.backupRetention()),
            MusicMetadataEdit.Status.PREPARED, null, null);
        preparedEdit = edits.save(preparedEdit);
        files.markReplacement(prepared.stage(), originalModifiedMillis);
        new SharedFolderPathResolver(music.root()).recheckForMutation(source);
        requireDiskRevision(source, update.expectedObservedToken());
        files.atomicReplace(prepared.stage(), source);
        MusicFileRevision replacement = observe(source);
        applied = true;
        MusicMetadataEdit savedEdit = edits.save(preparedEdit.applied(replacement.token()));
        MusicTrack refreshed = refresh(current, source, replacement, rewritten);
        return new MusicMetadataResult(
            savedEdit.id(), replacement.token(), savedEdit.expiresAt(), MusicTrackView.from(refreshed));
      } catch (RuntimeException failure) {
        if (!applied && preparedEdit != null) edits.deleteById(preparedEdit.id());
        throw failure;
      } finally {
        files.cleanup(prepared, applied);
      }
    });
  }

  public MusicMetadataResult undo(String editId, String expectedObservedToken) {
    access.requireWrite();
    requireIdentifier(editId);
    MusicMetadataEdit edit = edits.findById(editId).orElseThrow(this::notFound);
    if (edit.status() != MusicMetadataEdit.Status.APPLIED
        || edit.expiresAt().isBefore(clock.instant())) throw conflict();
    requireRevision(edit.replacementObservedToken(), expectedObservedToken);
    return locked(edit.trackId(), () -> {
      MusicMetadataEdit currentEdit = edits.findById(edit.id()).orElseThrow(this::notFound);
      if (currentEdit.status() != MusicMetadataEdit.Status.APPLIED
          || !expectedObservedToken.equals(currentEdit.replacementObservedToken())) throw conflict();
      MusicTrack current = ready(currentEdit.trackId());
      requireRevision(current.observedToken(), expectedObservedToken);
      Path source = source(currentEdit.sourcePath());
      requireDiskRevision(source, expectedObservedToken);
      long previousModifiedMillis = observe(source).modifiedMillis();
      String operationId = UUID.randomUUID().toString();
      Path stage = files.prepareUndo(currentEdit, operationId, extension(currentEdit.sourcePath()));
      try {
        MusicProbeResult restored = probe.probe(stage.toAbsolutePath().normalize());
        requireOriginalPreserved(currentEdit, restored);
        files.markReplacement(stage, previousModifiedMillis);
        new SharedFolderPathResolver(music.root()).recheckForMutation(source);
        requireDiskRevision(source, expectedObservedToken);
        files.atomicReplace(stage, source);
        MusicFileRevision replacement = observe(source);
        MusicMetadataEdit undone = edits.save(currentEdit.undone(clock.instant()));
        MusicTrack refreshed = refresh(current, source, replacement, restored);
        return new MusicMetadataResult(
            undone.id(), replacement.token(), undone.expiresAt(), MusicTrackView.from(refreshed));
      } finally {
        try {
          java.nio.file.Files.deleteIfExists(stage);
        } catch (IOException ignored) {
          // Retained private staging is safer than touching the source after a failed undo.
        }
      }
    });
  }

  @Scheduled(fixedDelayString = "${app.music.metadata.cleanup-delay:1h}")
  public void cleanupExpired() {
    scheduledCollectors.run("music-metadata-cleanup", CLEANUP_LEASE_DURATION, guard -> {
      for (MusicMetadataEdit edit
          : edits.findTop100ByExpiresAtBeforeOrderByExpiresAtAsc(clock.instant())) {
        guard.verifyHeld();
        try {
          files.delete(edit.backupFileName());
          guard.verifyHeld();
          edits.delete(edit);
        } catch (LeaseOwnershipLostException failure) {
          throw failure;
        } catch (RuntimeException ignored) {
          // A later bounded cleanup pass retries the same private artifact.
        }
      }
      return null;
    });
  }

  private MusicTrack refresh(
      MusicTrack previous,
      Path source,
      MusicFileRevision revision,
      MusicProbeResult metadata) {
    String artworkRevision = metadata.hasArtwork()
        ? artwork.extract(source, previous.path(), revision).orElse(null)
        : null;
    return tracks.save(MusicTrack.indexed(
        previous, previous.path(), revision.token(), metadata, artworkRevision, clock.instant()));
  }

  private <T> T locked(String trackId, java.util.concurrent.Callable<T> work) {
    synchronized (localLock) {
      String owner = UUID.randomUUID().toString();
      String leaseName = "music-metadata:" + trackId;
      var now = clock.instant();
      if (!leases.tryAcquire(leaseName, owner, now, now.plus(properties.leaseDuration()))) {
        throw conflict();
      }
      try {
        return work.call();
      } catch (ResponseStatusException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Music metadata edit failed.", failure);
      } finally {
        leases.release(leaseName, owner);
      }
    }
  }

  private MusicMetadataUpdate validate(MusicMetadataUpdate update) {
    if (update == null || !token(update.expectedObservedToken())) {
      throw badRequest("A valid expected Music revision is required.");
    }
    if (update.removeArtwork() && update.artworkDataUrl() != null) {
      throw badRequest("Artwork cannot be replaced and removed together.");
    }
    return new MusicMetadataUpdate(
        update.expectedObservedToken(), text(update.title()), text(update.artist()),
        text(update.albumArtist()), text(update.album()), number(update.trackNumber(), 1, 9999),
        number(update.discNumber(), 1, 999), text(update.genre()),
        number(update.year(), 1000, 9999), update.artworkDataUrl(), update.removeArtwork());
  }

  private String text(String value) {
    if (value == null) return null;
    String cleaned = value.strip();
    if (cleaned.length() > 300 || cleaned.codePoints().anyMatch(codePoint -> {
      int type = Character.getType(codePoint);
      return type == Character.CONTROL || type == Character.FORMAT;
    })) {
      throw badRequest("Music metadata text is invalid.");
    }
    return cleaned.isEmpty() ? null : cleaned;
  }

  private Integer number(Integer value, int minimum, int maximum) {
    if (value != null && (value < minimum || value > maximum)) {
      throw badRequest("Music metadata number is outside the supported range.");
    }
    return value;
  }

  private byte[] artwork(String dataUrl, boolean remove) {
    if (dataUrl == null || remove) return null;
    String prefix;
    boolean jpeg;
    if (dataUrl.startsWith("data:image/jpeg;base64,")) {
      prefix = "data:image/jpeg;base64,";
      jpeg = true;
    } else if (dataUrl.startsWith("data:image/png;base64,")) {
      prefix = "data:image/png;base64,";
      jpeg = false;
    } else {
      throw badRequest("Music artwork must be a JPEG or PNG data URL.");
    }
    try {
      String encoded = dataUrl.substring(prefix.length());
      long maximumEncoded = ((long) properties.artworkMaxBytes() + 2) / 3 * 4;
      if (encoded.length() > maximumEncoded) throw badRequest("Music artwork is invalid or too large.");
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (decoded.length < 8 || decoded.length > properties.artworkMaxBytes()
          || (jpeg ? !jpegMagic(decoded) : !pngMagic(decoded))) {
        throw badRequest("Music artwork is invalid or too large.");
      }
      return decoded;
    } catch (IllegalArgumentException invalid) {
      throw badRequest("Music artwork base64 is invalid.");
    }
  }

  private boolean jpegMagic(byte[] value) {
    return (value[0] & 0xff) == 0xff && (value[1] & 0xff) == 0xd8;
  }

  private boolean pngMagic(byte[] value) {
    return (value[0] & 0xff) == 0x89 && value[1] == 'P' && value[2] == 'N'
        && value[3] == 'G' && value[4] == 13 && value[5] == 10 && value[6] == 26 && value[7] == 10;
  }

  private void requireIdentifier(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
      throw badRequest("Music identifier is invalid.");
    }
  }

  private MusicTrack ready(String id) {
    return catalog.findReady(id).orElseThrow(this::notFound);
  }

  private Path source(String relative) {
    try {
      return new SharedFolderPathResolver(music.root()).existing(relative);
    } catch (RuntimeException failure) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Music file is unavailable.");
    }
  }

  private String extension(String path) {
    int dot = path.lastIndexOf('.');
    String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (!WRITABLE_EXTENSIONS.contains(extension)) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "This Music format is read-only.");
    }
    return extension;
  }

  private void requireDiskRevision(Path source, String expected) {
    if (!observe(source).token().equals(expected)) throw conflict();
  }

  private MusicFileRevision observe(Path source) {
    try {
      return MusicFileRevision.observe(source);
    } catch (IOException failure) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Music file changed or disappeared.");
    }
  }

  private void requirePreserved(MusicTrack original, MusicProbeResult rewritten) {
    if (!original.audioCodec().equals(rewritten.audioCodec())
        || Math.abs(original.durationSeconds() - rewritten.durationSeconds())
            > DURATION_TOLERANCE_SECONDS) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Metadata rewrite did not preserve the audio stream.");
    }
  }

  private void requireOriginalPreserved(MusicMetadataEdit edit, MusicProbeResult restored) {
    if (!edit.originalAudioCodec().equals(restored.audioCodec())
        || Math.abs(edit.originalDurationSeconds() - restored.durationSeconds())
            > DURATION_TOLERANCE_SECONDS) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Music metadata backup did not preserve the audio stream.");
    }
  }

  private void requireRevision(String actual, String expected) {
    if (!token(expected) || !expected.equals(actual)) throw conflict();
  }

  private boolean token(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(HttpStatus.CONFLICT, "Music file changed. Refresh and retry.");
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Music metadata edit was not found.");
  }
}

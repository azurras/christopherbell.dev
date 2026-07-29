package dev.christopherbell.sharedfolder.radio;

import dev.christopherbell.music.catalog.MusicIndexStatus;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioDurationRequest;
import org.springframework.stereotype.Component;

/** Resolves duration only from a present ready Music row for the exact file revision. */
@Component
public final class SharedFolderRadioDurationResolver {
  private final MusicTrackRepository tracks;

  public SharedFolderRadioDurationResolver(MusicTrackRepository tracks) {
    if (tracks == null) throw new IllegalArgumentException("Music track repository is required");
    this.tracks = tracks;
  }

  /** Returns trusted probe seconds, or null when metadata is absent, stale, or unsafe. */
  public Double resolve(SharedDirectoryEntry entry) {
    if (entry == null || entry.type() != SharedDirectoryEntryType.FILE
        || entry.previewKind() != SharedFolderPreviewKind.AUDIO
        || entry.observedToken() == null || entry.observedToken().isBlank()) return null;
    String path = entry.path();
    int separator = path == null ? -1 : path.indexOf('/');
    if (separator < 1 || !path.substring(0, separator).equalsIgnoreCase("Music")) return null;
    String musicPath = path.substring(separator + 1);
    MusicTrack track = tracks.findByPath(musicPath).orElse(null);
    if (track == null || track.indexStatus() != MusicIndexStatus.READY || track.missingSince() != null
        || !musicPath.equals(track.path())
        || !entry.observedToken().equals(track.observedToken())
        || !SharedFolderRadioDurationRequest.isValidDuration(track.durationSeconds())) return null;
    return track.durationSeconds();
  }
}

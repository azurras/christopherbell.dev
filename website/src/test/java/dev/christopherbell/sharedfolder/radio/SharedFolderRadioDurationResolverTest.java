package dev.christopherbell.sharedfolder.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.catalog.MusicTrackRepository;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SharedFolderRadioDurationResolverTest {
  private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

  @Test
  void exactPresentReadyRevisionReturnsTrustedProbeDuration() {
    MusicTrackRepository tracks = mock(MusicTrackRepository.class);
    when(tracks.findByPath("Album/song.mp3")).thenReturn(Optional.of(
        MusicTrack.ready("Album/song.mp3", "revision-1", probe(123.5), null, NOW)));
    SharedFolderRadioDurationResolver resolver = new SharedFolderRadioDurationResolver(tracks);

    assertThat(resolver.resolve(entry("Music/Album/song.mp3", "revision-1")))
        .isEqualTo(123.5);
  }

  @Test
  void staleMissingAndOutsideMusicEntriesNeverReturnDuration() {
    MusicTrackRepository tracks = mock(MusicTrackRepository.class);
    MusicTrack ready = MusicTrack.ready(
        "Album/song.mp3", "revision-1", probe(123.5), null, NOW);
    when(tracks.findByPath("Album/song.mp3")).thenReturn(Optional.of(ready));
    when(tracks.findByPath("Album/missing.mp3")).thenReturn(Optional.of(
        MusicTrack.ready("Album/missing.mp3", "revision-2", probe(50), null, NOW)
            .markMissing(NOW)));
    SharedFolderRadioDurationResolver resolver = new SharedFolderRadioDurationResolver(tracks);

    assertThat(resolver.resolve(entry("Music/Album/song.mp3", "forged-revision"))).isNull();
    assertThat(resolver.resolve(entry("Music/Album/missing.mp3", "revision-2"))).isNull();
    assertThat(resolver.resolve(entry("Other/song.mp3", "revision-1"))).isNull();
  }

  private SharedDirectoryEntry entry(String path, String token) {
    return new SharedDirectoryEntry(
        path.substring(path.lastIndexOf('/') + 1), path, SharedDirectoryEntryType.FILE, 1, NOW,
        SharedFolderPreviewKind.AUDIO, token);
  }

  private MusicProbeResult probe(double duration) {
    return new MusicProbeResult(
        "Song", "Artist", null, "Album", 1, 1, null, 2026,
        duration, "aac", "m4a", false);
  }
}

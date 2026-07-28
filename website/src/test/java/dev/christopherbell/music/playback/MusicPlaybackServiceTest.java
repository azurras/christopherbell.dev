package dev.christopherbell.music.playback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicFileRevision;
import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.security.MusicAccessService;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;

class MusicPlaybackServiceTest {
  @TempDir Path temporary;

  @Test
  void opensOnlyCatalogTrackAndCopiesOneExactByteRange() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("Music"));
    Path song = Files.write(root.resolve("song.m4a"), "0123456789".getBytes());
    MusicTrack track = track("song.m4a", MusicFileRevision.observe(song).token());
    var catalog = mock(MusicCatalog.class);
    var access = mock(MusicAccessService.class);
    when(catalog.findReady(track.id())).thenReturn(Optional.of(track));
    var service = new MusicPlaybackService(properties(root), catalog, access);

    try (var selection = service.open(track.id(), "bytes=2-5")) {
      var output = new ByteArrayOutputStream();
      selection.copyTo(output);

      assertThat(output.toString()).isEqualTo("2345");
      assertThat(selection.partial()).isTrue();
      assertThat(selection.mediaType().toString()).isEqualTo("audio/mp4");
      assertThat(selection.filename()).isEqualTo("song.m4a");
    }
  }

  @Test
  void rejectsStaleCatalogRevisionBeforeOpeningFile() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("Music"));
    Files.writeString(root.resolve("song.flac"), "new bytes");
    MusicTrack track = track("song.flac", "stale-token");
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(track.id())).thenReturn(Optional.of(track));
    var service = new MusicPlaybackService(
        properties(root), catalog, mock(MusicAccessService.class));

    assertThatThrownBy(() -> service.open(track.id(), null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");
  }

  @Test
  void rejectsMultipleRanges() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("Music"));
    Path song = Files.writeString(root.resolve("song.mp3"), "0123456789");
    MusicTrack track = track("song.mp3", MusicFileRevision.observe(song).token());
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(track.id())).thenReturn(Optional.of(track));
    var service = new MusicPlaybackService(
        properties(root), catalog, mock(MusicAccessService.class));

    assertThatThrownBy(() -> service.open(track.id(), "bytes=0-1,3-4"))
        .isInstanceOf(SharedFolderRangeNotSatisfiableException.class);
  }

  @Test
  void preservesFreshAuthorizationDenialInsteadOfMaskingItAsAFileConflict() {
    var access = mock(MusicAccessService.class);
    doThrow(new AccessDeniedException("Music read access required")).when(access).requireRead();
    var service = new MusicPlaybackService(
        properties(temporary.resolve("missing")), mock(MusicCatalog.class), access);

    assertThatThrownBy(() -> service.open("track-1", null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Music read access required");
  }

  private MusicTrack track(String path, String token) {
    return MusicTrack.ready(path, token, new MusicProbeResult(
        "Song", "Artist", "Artist", "Album", 1, 1, "Genre", 2026,
        180, "aac", "mov,mp4,m4a", true), "a".repeat(64), Instant.now());
  }

  private MusicProperties properties(Path root) {
    return new MusicProperties(
        root, temporary.resolve("artwork"), "ffprobe", "ffmpeg", 100,
        Duration.ofMinutes(1), Duration.ofSeconds(10), 1024 * 1024,
        5 * 1024 * 1024, 1024, true);
  }
}

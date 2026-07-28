package dev.christopherbell.music.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.music.catalog.MusicArtworkService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.playback.MusicPlaybackSelection;
import dev.christopherbell.music.playback.MusicPlaybackService;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

class MusicReadControllerTest {

  @Test
  void streamIsInlineNoStoreAndCarriesExactPartialRangeHeaders() {
    var playback = mock(MusicPlaybackService.class);
    when(playback.open("track-1", "bytes=2-5")).thenReturn(new MusicPlaybackSelection(
        new ByteArrayInputStream("2345".getBytes()), "song.flac",
        MediaType.parseMediaType("audio/flac"), 2, 4, 10, true));
    var controller = new MusicReadController(
        mock(MusicCatalog.class), playback, mock(MusicArtworkService.class), properties());
    var requestHeaders = new HttpHeaders();
    requestHeaders.add(HttpHeaders.RANGE, "bytes=2-5");

    var response = controller.stream("track-1", requestHeaders);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .startsWith("inline;").doesNotContain("attachment");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
        .isEqualTo("bytes 2-5/10");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .isEqualTo("private, no-store");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void controllerDefinesNoMusicDownloadRoute() {
    boolean hasDownloadRoute = Arrays.stream(MusicReadController.class.getDeclaredMethods())
        .flatMap(method -> {
          var mapping = method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
          return mapping == null ? java.util.stream.Stream.<String>empty()
              : Arrays.stream(mapping.value());
        })
        .anyMatch(path -> path.toLowerCase(java.util.Locale.ROOT).contains("download"));

    assertThat(hasDownloadRoute).isFalse();
  }

  private MusicProperties properties() {
    return new MusicProperties(
        java.nio.file.Path.of("Music"), java.nio.file.Path.of("artwork"),
        "ffprobe", "ffmpeg", 100, Duration.ofMinutes(1), Duration.ofSeconds(10),
        1024 * 1024, 5 * 1024 * 1024, 1024, true);
  }
}

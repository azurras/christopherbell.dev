package dev.christopherbell.music.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.music.catalog.MusicArtworkService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicCatalogResult;
import dev.christopherbell.music.catalog.MusicFacets;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicQuery;
import dev.christopherbell.music.library.MusicLibraryService;
import dev.christopherbell.music.playback.MusicPlaybackSelection;
import dev.christopherbell.music.playback.MusicPlaybackService;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
        mock(MusicCatalog.class), mock(MusicLibraryService.class), playback,
        mock(MusicArtworkService.class), properties());
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
  void catalogResolvesFavoritePlaylistAndPageConstraintsBeforeSearching() {
    var catalog = mock(MusicCatalog.class);
    var query = ArgumentCaptor.forClass(MusicQuery.class);
    when(catalog.search(query.capture())).thenReturn(new MusicCatalogResult(
        List.of(), List.of(), new MusicFacets(List.of(), List.of(), List.of(), List.of()),
        1, 50, 75, 2));
    var library = mock(MusicLibraryService.class);
    when(library.playlistTrackIds("playlist-1")).thenReturn(List.of("track-a", "track-b"));
    var playback = mock(MusicPlaybackService.class);
    var controller = new MusicReadController(
        catalog, library, playback, mock(MusicArtworkService.class), properties());

    var response = controller.catalog(
        "mix", "Artist", "Album", "Rock", true, "playlist-1", 1, 50, null);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().totalTracks()).isEqualTo(75);
    assertThat(query.getValue()).isEqualTo(new MusicQuery(
        "mix", "Artist", "Album", "Rock", true,
        List.of("track-a", "track-b"), 1, 50));
    verify(playback).requireCatalogRead();
    verify(library).playlistTrackIds(eq("playlist-1"));
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

package dev.christopherbell.music.web;

import static dev.christopherbell.libs.api.APIVersion.V20260728;

import dev.christopherbell.music.catalog.MusicArtworkService;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicQuery;
import dev.christopherbell.music.library.MusicLibraryService;
import dev.christopherbell.music.playback.MusicPlaybackService;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Fresh-authorized catalog, artwork, and inline-only Music playback API. */
@RestController
@RequestMapping("/api/music" + V20260728)
public final class MusicReadController {
  private final MusicCatalog catalog;
  private final MusicLibraryService library;
  private final MusicPlaybackService playback;
  private final MusicArtworkService artwork;
  private final MusicProperties properties;

  public MusicReadController(
      MusicCatalog catalog,
      MusicLibraryService library,
      MusicPlaybackService playback,
      MusicArtworkService artwork,
      MusicProperties properties) {
    this.catalog = catalog;
    this.library = library;
    this.playback = playback;
    this.artwork = artwork;
    this.properties = properties;
  }

  @GetMapping("/catalog")
  public ResponseEntity<MusicCatalogView> catalog(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String artist,
      @RequestParam(required = false) String album,
      @RequestParam(required = false) String genre,
      @RequestParam(required = false) Boolean favorite,
      @RequestParam(required = false) String playlistId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) Integer limit) {
    playback.requireCatalogRead();
    int requestedSize = limit == null ? size : limit;
    var trackIds = playlistId == null || playlistId.isBlank()
        ? null
        : library.playlistTrackIds(playlistId);
    var result = catalog.search(new MusicQuery(
        q, artist, album, genre, favorite, trackIds, page, requestedSize));
    return ResponseEntity.ok().headers(noStore()).body(MusicCatalogView.from(result));
  }

  @GetMapping("/tracks/{id}/stream")
  public ResponseEntity<StreamingResponseBody> stream(
      @PathVariable String id,
      @RequestHeader HttpHeaders requestHeaders) {
    try {
      var selection = playback.open(id, joinedRange(requestHeaders));
      HttpHeaders headers = noStore();
      headers.setContentType(selection.mediaType());
      headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
      headers.setContentLength(selection.length());
      headers.setContentDisposition(ContentDisposition.inline()
          .filename(selection.filename(), StandardCharsets.UTF_8).build());
      if (selection.partial()) {
        headers.set(HttpHeaders.CONTENT_RANGE,
            "bytes " + selection.start() + '-' + (selection.start() + selection.length() - 1)
                + '/' + selection.totalLength());
      }
      StreamingResponseBody body = output -> {
        try (selection) {
          selection.copyTo(output);
        }
      };
      return new ResponseEntity<>(
          body, headers, selection.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    } catch (SharedFolderRangeNotSatisfiableException invalid) {
      return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
          .headers(noStore())
          .header(HttpHeaders.ACCEPT_RANGES, "bytes")
          .header(HttpHeaders.CONTENT_RANGE, "bytes */" + invalid.totalLength())
          .build();
    }
  }

  @GetMapping("/tracks/{id}/artwork")
  public ResponseEntity<byte[]> artwork(@PathVariable String id) {
    var track = playback.requireReadyTrack(id);
    if (track.artworkRevision() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Music artwork was not found.");
    }
    var source = artwork.resolve(track.artworkRevision()).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Music artwork was not found."));
    try {
      long size = Files.size(source);
      if (size < 1 || size > properties.artworkMaxBytes()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Music artwork was not found.");
      }
      return ResponseEntity.ok().headers(noStore()).contentType(MediaType.IMAGE_JPEG)
          .contentLength(size).body(Files.readAllBytes(source));
    } catch (IOException failure) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Music artwork is unavailable.");
    }
  }

  private HttpHeaders noStore() {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl("private, no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    return headers;
  }

  private String joinedRange(HttpHeaders headers) {
    var values = headers.get(HttpHeaders.RANGE);
    return values == null || values.isEmpty() ? null : String.join(",", values);
  }
}

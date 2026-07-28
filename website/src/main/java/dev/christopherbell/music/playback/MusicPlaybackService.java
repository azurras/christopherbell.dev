package dev.christopherbell.music.playback;

import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicFileRevision;
import dev.christopherbell.music.catalog.MusicProperties;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.security.MusicAccessService;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Authorizes and safely opens catalog-addressed Music streams without a download path API. */
@Service
public final class MusicPlaybackService {
  private final MusicProperties properties;
  private final MusicCatalog catalog;
  private final MusicAccessService access;

  public MusicPlaybackService(
      MusicProperties properties,
      MusicCatalog catalog,
      MusicAccessService access) {
    this.properties = properties;
    this.catalog = catalog;
    this.access = access;
  }

  public MusicPlaybackSelection open(String trackId, String rangeHeader) {
    access.requireRead();
    requireEnabled();
    MusicTrack track = readyTrack(trackId);
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      Path selected = resolver.existing(track.path());
      var handle = resolver.readHandle(selected);
      var revision = MusicFileRevision.observe(handle.attributes());
      if (!track.playable(revision.token())) {
        throw conflict();
      }
      var range = selectRange(rangeHeader, revision.size());
      InputStream input = handle.openFile();
      return new MusicPlaybackSelection(
          input,
          filename(track.path()),
          mediaType(track.path()),
          range.start(),
          range.length(),
          revision.size(),
          range.partial());
    } catch (AccessDeniedException | ResponseStatusException exception) {
      throw exception;
    } catch (IOException | RuntimeException failure) {
      throw conflict();
    }
  }

  public MusicTrack requireReadyTrack(String trackId) {
    access.requireRead();
    requireEnabled();
    return readyTrack(trackId);
  }

  /** Performs only the fresh Music-read check needed before a bounded catalog query. */
  public void requireCatalogRead() {
    access.requireRead();
    requireEnabled();
  }

  private MusicTrack readyTrack(String trackId) {
    return catalog.findReady(trackId).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Music track was not found."));
  }

  private RangeSelection selectRange(String header, long total) {
    if (header == null || header.isBlank()) {
      return new RangeSelection(0, total, false);
    }
    try {
      List<HttpRange> ranges = HttpRange.parseRanges(header);
      if (ranges.size() != 1 || total == 0) {
        throw invalidRange(total);
      }
      long start = ranges.getFirst().getRangeStart(total);
      long end = ranges.getFirst().getRangeEnd(total);
      if (start < 0 || end < start || end >= total) {
        throw invalidRange(total);
      }
      return new RangeSelection(start, end - start + 1, true);
    } catch (IllegalArgumentException invalid) {
      throw invalidRange(total);
    }
  }

  private MediaType mediaType(String path) {
    String extension = extension(path);
    return switch (extension) {
      case "mp3" -> MediaType.parseMediaType("audio/mpeg");
      case "m4a", "mp4" -> MediaType.parseMediaType("audio/mp4");
      case "flac" -> MediaType.parseMediaType("audio/flac");
      case "ogg", "oga", "opus" -> MediaType.parseMediaType("audio/ogg");
      case "wav" -> MediaType.parseMediaType("audio/wav");
      case "aac" -> MediaType.parseMediaType("audio/aac");
      case "webm" -> MediaType.parseMediaType("audio/webm");
      default -> MediaType.APPLICATION_OCTET_STREAM;
    };
  }

  private String extension(String path) {
    int separator = path.lastIndexOf('/');
    int dot = path.lastIndexOf('.');
    return dot > separator ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
  }

  private String filename(String path) {
    int separator = path.lastIndexOf('/');
    return separator >= 0 ? path.substring(separator + 1) : path;
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Music is unavailable.");
    }
  }

  private ResponseStatusException conflict() {
    return new ResponseStatusException(
        HttpStatus.CONFLICT, "Music track changed and is being reindexed.");
  }

  private SharedFolderRangeNotSatisfiableException invalidRange(long total) {
    return new SharedFolderRangeNotSatisfiableException(total);
  }

  private record RangeSelection(long start, long length, boolean partial) {}
}

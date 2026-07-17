package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opens validated shared-folder files as bounded disk-backed download regions. */
@Service
public class SharedFolderDownloadService {
  private final SharedFolderProperties properties;

  /** Creates the download service from the configured shared-folder boundary. */
  public SharedFolderDownloadService(SharedFolderProperties properties) {
    this.properties = properties;
  }

  /**
   * Opens one decoded relative file path for a full or single-range download.
   *
   * <p>The returned {@link FileSystemResource} streams from disk. This method does not buffer a
   * file into memory or URL-decode an already decoded HTTP value.
   *
   * @param decodedPath decoded relative file path
   * @param rangeHeader one raw HTTP {@code Range} header value, or {@code null}
   * @return a safe file resource and the selected byte region
   */
  public SharedFolderDownload open(String decodedPath, String rangeHeader) {
    Path file = resolveFile(decodedPath);
    try {
      long totalLength = Files.size(file);
      RangeSelection selection = rangeSelection(rangeHeader, totalLength);
      String name = file.getFileName().toString();
      return new SharedFolderDownload(
          new FileSystemResource(file),
          selection.start(),
          selection.length(),
          totalLength,
          selection.partial(),
          SharedFolderContentPolicy.mediaType(name, SharedFolderContentPolicy.previewKind(name)),
          SharedFolderContentPolicy.attachmentDisposition(name));
    } catch (IOException exception) {
      throw notFound();
    }
  }

  private Path resolveFile(String decodedPath) {
    if (!properties.enabled() || decodedPath == null) {
      throw notFound();
    }
    try {
      Path file = new SharedFolderPathResolver(properties.root()).existing(decodedPath);
      BasicFileAttributes attributes = Files.readAttributes(
          file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()) {
        throw notFound();
      }
      return file;
    } catch (IOException | UnsafeSharedPathException exception) {
      throw notFound();
    }
  }

  private RangeSelection rangeSelection(String rangeHeader, long totalLength) {
    if (rangeHeader == null) {
      return new RangeSelection(0, totalLength, false);
    }
    try {
      List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
      if (ranges.size() != 1 || totalLength == 0) {
        throw rangeNotSatisfiable(totalLength);
      }
      HttpRange range = ranges.getFirst();
      long start = range.getRangeStart(totalLength);
      long end = range.getRangeEnd(totalLength);
      if (start < 0 || end < start || end >= totalLength) {
        throw rangeNotSatisfiable(totalLength);
      }
      return new RangeSelection(start, end - start + 1, true);
    } catch (IllegalArgumentException exception) {
      throw rangeNotSatisfiable(totalLength);
    }
  }

  private SharedFolderRangeNotSatisfiableException rangeNotSatisfiable(long totalLength) {
    return new SharedFolderRangeNotSatisfiableException(totalLength);
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared folder item was not found");
  }

  private record RangeSelection(long start, long length, boolean partial) {}

  /** Disk-backed download response metadata that contains no absolute filesystem path. */
  public record SharedFolderDownload(
      Resource resource,
      long start,
      long length,
      long totalLength,
      boolean partial,
      MediaType mediaType,
      String disposition) {}
}

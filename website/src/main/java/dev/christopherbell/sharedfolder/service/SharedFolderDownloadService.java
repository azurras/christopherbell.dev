package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderReadResource;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver.ReadHandle;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderReadBoundary;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderReadBoundary.NativeReadTarget;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opens validated shared-folder files as bounded disk-backed download regions. */
@Service
public class SharedFolderDownloadService {
  private final SharedFolderProperties properties;
  private final WindowsSharedFolderReadBoundary nativeBoundary;

  /** Creates the download service from the configured shared-folder boundary. */
  public SharedFolderDownloadService(SharedFolderProperties properties) {
    this(properties, WindowsSharedFolderReadBoundary.inactive());
  }

  /** Creates the Spring service with native reads activated only by the held-root singleton. */
  @Autowired
  public SharedFolderDownloadService(
      SharedFolderProperties properties, WindowsSharedFolderReadBoundary nativeBoundary) {
    this.properties = properties;
    this.nativeBoundary = nativeBoundary;
  }

  /**
   * Opens one decoded relative file path for a full or single-range download.
   *
   * <p>The returned resource streams from disk. It rechecks the resolver-owned handle when Spring
   * actually opens the stream, and never URL-decodes an already decoded HTTP value.
   *
   * @param decodedPath decoded relative file path
   * @param rangeHeader one raw HTTP {@code Range} header value, or {@code null}
   * @return a safe file resource and the selected byte region
   */
  public SharedFolderDownload open(String decodedPath, String rangeHeader) {
    if (nativeBoundary.nativeMode()) {
      return openNative(decodedPath, rangeHeader);
    }
    ResolvedFile file = resolveFile(decodedPath);
    long totalLength = file.attributes().size();
    RangeSelection selection = rangeSelection(rangeHeader, totalLength);
    String name = file.name();
    Resource resource = new SharedFolderReadResource(file.handle(), name, totalLength);
    return new SharedFolderDownload(
        selectedResource(resource, selection),
        selection.start(),
        selection.length(),
        totalLength,
        selection.partial(),
        SharedFolderContentPolicy.mediaType(name, SharedFolderContentPolicy.previewKind(name)),
        SharedFolderContentPolicy.attachmentDisposition(name));
  }

  private SharedFolderDownload openNative(String decodedPath, String rangeHeader) {
    if (!properties.enabled() || decodedPath == null) {
      throw notFound();
    }
    try {
      NativeReadTarget target = nativeBoundary.file(decodedPath);
      long totalLength = target.metadata().size();
      RangeSelection selection = rangeSelection(rangeHeader, totalLength);
      String name = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
      return new SharedFolderDownload(
          selectedResource(target.resource(name), selection),
          selection.start(), selection.length(), totalLength, selection.partial(),
          SharedFolderContentPolicy.mediaType(name, SharedFolderContentPolicy.previewKind(name)),
          SharedFolderContentPolicy.attachmentDisposition(name));
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    }
  }

  private ResolvedFile resolveFile(String decodedPath) {
    if (!properties.enabled() || decodedPath == null) {
      throw notFound();
    }
    try {
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      ReadHandle handle = resolver.readHandle(resolver.existing(decodedPath));
      BasicFileAttributes attributes = handle.attributes();
      if (!attributes.isRegularFile()) {
        throw notFound();
      }
      String name = decodedPath.substring(decodedPath.lastIndexOf('/') + 1);
      return new ResolvedFile(handle, name, attributes);
    } catch (UnsafeSharedPathException exception) {
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

  private Resource selectedResource(Resource resource, RangeSelection selection) {
    if (!selection.partial()) {
      return resource;
    }
    return new SharedFolderByteRangeResource(resource, selection.start(), selection.length());
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared folder item was not found");
  }

  private record RangeSelection(long start, long length, boolean partial) {}

  private record ResolvedFile(ReadHandle handle, String name, BasicFileAttributes attributes) {}

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

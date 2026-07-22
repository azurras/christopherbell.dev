package dev.christopherbell.sharedfolder.media;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderReadBoundary;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Resolves one existing ordinary source and captures the exact revision sent to the worker. */
@Component
public final class MediaSourceBoundary {
  private final SharedFolderProperties properties;
  private final WindowsSharedFolderReadBoundary nativeBoundary;

  public MediaSourceBoundary(
      SharedFolderProperties properties, WindowsSharedFolderReadBoundary nativeBoundary) {
    this.properties = properties;
    this.nativeBoundary = nativeBoundary;
  }

  public MediaSourceSnapshot resolve(String path) {
    if (!properties.enabled() || path == null) throw notFound();
    try {
      var segments = SharedFolderPathResolver.safeRelativeSegments(path, false);
      Path absolute = properties.root().toAbsolutePath().normalize();
      for (String segment : segments) absolute = absolute.resolve(segment);
      absolute = absolute.normalize();
      if (!absolute.startsWith(properties.root().toAbsolutePath().normalize())) throw notFound();
      if (nativeBoundary.nativeMode()) {
        var metadata = nativeBoundary.mediaFileMetadata(path);
        return new MediaSourceSnapshot(path, absolute, metadata.size(), metadata.modifiedAt());
      }
      SharedFolderPathResolver resolver = new SharedFolderPathResolver(properties.root());
      Path existing = resolver.existing(path);
      var handle = resolver.readHandle(existing);
      BasicFileAttributes attributes = handle.attributes();
      if (!attributes.isRegularFile()) throw notFound();
      return new MediaSourceSnapshot(
          path, existing.toAbsolutePath().normalize(), attributes.size(),
          attributes.lastModifiedTime().toInstant());
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    }
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared folder media was not found");
  }

  /** Canonical worker input plus revision metadata; this object is never returned by an API. */
  public record MediaSourceSnapshot(
      String relativePath, Path absolutePath, long size, Instant modifiedAt) {
    /** Collapses Windows case aliases without merging distinct case-sensitive provider paths. */
    public String cacheIdentity() {
      return java.io.File.separatorChar == '\\'
          ? relativePath.toLowerCase(java.util.Locale.ROOT) : relativePath;
    }
  }
}

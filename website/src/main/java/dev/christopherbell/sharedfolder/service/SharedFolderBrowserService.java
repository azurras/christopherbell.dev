package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver.ReadHandle;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedDirectoryResponse;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Lists ordinary shared-folder entries while exposing only validated relative metadata. */
@Service
public class SharedFolderBrowserService {
  private final SharedFolderProperties properties;

  /** Creates the browser from the configured shared-folder boundary. */
  public SharedFolderBrowserService(SharedFolderProperties properties) {
    this.properties = properties;
  }

  /**
   * Lists one decoded relative directory path.
   *
   * <p>The HTTP layer decodes the query parameter exactly once. This service intentionally never
   * URL-decodes it again and returns only the same validated relative form.
   *
   * @param decodedPath decoded relative directory path, or empty for the root
   * @return sorted public-safe metadata
   */
  public SharedDirectoryResponse list(String decodedPath) {
    String requestedPath = requirePath(decodedPath);
    SharedFolderPathResolver resolver = resolver();
    Path directory = resolveExisting(resolver, requestedPath);
    try {
      ReadHandle directoryHandle = resolver.readHandle(directory);
      BasicFileAttributes attributes = directoryHandle.attributes();
      if (!attributes.isDirectory()) {
        throw notFound();
      }
      List<SharedDirectoryEntry> entries = new ArrayList<>();
      try (DirectoryStream<Path> children = directoryHandle.openDirectory()) {
        for (Path child : children) {
          String name = child.getFileName().toString();
          String relativePath = requestedPath.isEmpty() ? name : requestedPath + "/" + name;
          Path safeChild = resolveExisting(resolver, relativePath);
          BasicFileAttributes childAttributes = resolver.readHandle(safeChild).attributes();
          SharedDirectoryEntryType type = childAttributes.isDirectory()
              ? SharedDirectoryEntryType.DIRECTORY : SharedDirectoryEntryType.FILE;
          if (!childAttributes.isDirectory() && !childAttributes.isRegularFile()) {
            continue;
          }
          entries.add(new SharedDirectoryEntry(
              name,
              relativePath,
              type,
              childAttributes.isRegularFile() ? childAttributes.size() : 0,
              childAttributes.lastModifiedTime().toInstant(),
              childAttributes.isRegularFile()
                  ? SharedFolderContentPolicy.previewKind(name)
                  : dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind.NONE));
        }
      }
      entries.sort(Comparator.comparing(SharedDirectoryEntry::name, String.CASE_INSENSITIVE_ORDER));
      return new SharedDirectoryResponse(requestedPath, List.copyOf(entries));
    } catch (IOException | SecurityException exception) {
      throw notFound();
    }
  }

  private SharedFolderPathResolver resolver() {
    if (!properties.enabled()) {
      throw notFound();
    }
    try {
      return new SharedFolderPathResolver(properties.root());
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    }
  }

  private Path resolveExisting(SharedFolderPathResolver resolver, String path) {
    try {
      return resolver.existing(path);
    } catch (UnsafeSharedPathException exception) {
      throw notFound();
    }
  }

  private String requirePath(String path) {
    if (path == null) {
      throw notFound();
    }
    return path;
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Shared folder item was not found");
  }
}

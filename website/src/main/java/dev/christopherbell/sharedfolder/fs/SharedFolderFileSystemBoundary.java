package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Filesystem facts the shared-folder resolver must prove before returning an operation path.
 *
 * <p>The interface is a production boundary, not test-only indirection: it keeps canonical path,
 * file-store, reparse-point, and mount decisions together so every resolver decision has the same
 * filesystem semantics.
 */
public interface SharedFolderFileSystemBoundary {
  /** Returns an absolute lexical path with dot segments removed. */
  Path absoluteNormalized(Path path);

  /** Returns whether a path exists without resolving a link. */
  boolean existsNoFollow(Path path);

  /** Reads basic attributes without resolving a link. */
  BasicFileAttributes readAttributesNoFollow(Path path) throws IOException;

  /** Returns the canonical filesystem path with links resolved. */
  Path realPath(Path path) throws IOException;

  /** Returns the canonical filesystem path while refusing to resolve links. */
  Path realPathNoFollow(Path path) throws IOException;

  /** Returns whether two existing paths remain on the same filesystem store. */
  boolean sameFileStore(Path first, Path second) throws IOException;

  /** Returns whether a path is a filesystem mount boundary. */
  boolean isMountPoint(Path path) throws IOException;

  /** Returns native DOS attributes without resolving a link when the provider supports them. */
  Object dosAttributesNoFollow(Path path) throws IOException;

  /** Opens an already revalidated directory for one listing operation. */
  DirectoryStream<Path> openDirectory(Path path) throws IOException;

  /**
   * Opens an already revalidated ordinary file for reading without following a link where the
   * provider supports that option.
   */
  InputStream openFileNoFollow(Path path) throws IOException;
}

package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Supplies operating-system mount facts for canonical filesystem paths.
 *
 * <p>Implementations must throw when they cannot determine whether a path is a mount point so
 * callers can deny access rather than treating unavailable metadata as an ordinary directory.
 */
@FunctionalInterface
public interface SharedFolderMountMetadata {
  /**
   * Determines whether the supplied canonical path is a filesystem mount point.
   *
   * @param canonicalPath canonical path to inspect
   * @return whether the path is a mount point
   * @throws IOException when mount facts are unavailable or malformed
   */
  boolean isMountPoint(Path canonicalPath) throws IOException;
}

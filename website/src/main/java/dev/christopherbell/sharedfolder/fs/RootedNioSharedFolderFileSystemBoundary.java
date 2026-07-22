package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** NIO filesystem effects restricted to one configured shared-folder root. */
final class RootedNioSharedFolderFileSystemBoundary implements SharedFolderFileSystemBoundary {
  private final Path root;
  private final NioSharedFolderFileSystemBoundary delegate;

  RootedNioSharedFolderFileSystemBoundary(Path root) {
    if (root == null) {
      throw new UnsafeSharedPathException("Shared-folder root is required");
    }
    this.root = root.toAbsolutePath().normalize();
    this.delegate = new NioSharedFolderFileSystemBoundary();
  }

  @Override
  public Path absoluteNormalized(Path path) {
    return contained(path);
  }

  @Override
  public boolean existsNoFollow(Path path) {
    return delegate.existsNoFollow(contained(path));
  }

  @Override
  public BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
    return delegate.readAttributesNoFollow(contained(path));
  }

  @Override
  public Path realPath(Path path) throws IOException {
    return delegate.realPath(contained(path));
  }

  @Override
  public Path realPathNoFollow(Path path) throws IOException {
    return delegate.realPathNoFollow(contained(path));
  }

  @Override
  public boolean sameFileStore(Path first, Path second) throws IOException {
    return delegate.sameFileStore(contained(first), contained(second));
  }

  @Override
  public boolean isMountPoint(Path path) throws IOException {
    return delegate.isMountPoint(contained(path));
  }

  @Override
  public Object dosAttributesNoFollow(Path path) throws IOException {
    return delegate.dosAttributesNoFollow(contained(path));
  }

  @Override
  public DirectoryStream<Path> openDirectory(Path path) throws IOException {
    return delegate.openDirectory(contained(path));
  }

  @Override
  public InputStream openFileNoFollow(Path path) throws IOException {
    return delegate.openFileNoFollow(contained(path));
  }

  private Path contained(Path path) {
    if (path == null) {
      throw new UnsafeSharedPathException("Shared-folder path is required");
    }
    Path candidate = path.toAbsolutePath().normalize();
    if (!candidate.startsWith(root)) {
      throw new UnsafeSharedPathException("Shared-folder path escapes the configured root");
    }
    return candidate;
  }
}

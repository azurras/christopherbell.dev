package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Real NIO-backed implementation of the shared-folder filesystem boundary. */
public class NioSharedFolderFileSystemBoundary implements SharedFolderFileSystemBoundary {
  private final SharedFolderMountMetadata mountMetadata;

  /** Creates the default fail-closed boundary for the current operating-system provider. */
  public NioSharedFolderFileSystemBoundary() {
    this(defaultMountMetadata());
  }

  /**
   * Creates a boundary with explicit mount metadata for a controlled filesystem provider.
   *
   * @param mountMetadata source of canonical mount facts; unavailable facts must throw
   */
  public NioSharedFolderFileSystemBoundary(SharedFolderMountMetadata mountMetadata) {
    this.mountMetadata = Objects.requireNonNull(mountMetadata, "mount metadata is required");
  }

  @Override
  public Path absoluteNormalized(Path path) {
    return path.toAbsolutePath().normalize();
  }

  @Override
  public boolean existsNoFollow(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public Path realPath(Path path) throws IOException {
    return path.toRealPath();
  }

  @Override
  public Path realPathNoFollow(Path path) throws IOException {
    return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public boolean sameFileStore(Path first, Path second) throws IOException {
    FileStore firstStore = Files.getFileStore(first);
    FileStore secondStore = Files.getFileStore(second);
    return firstStore.equals(secondStore);
  }

  @Override
  public boolean isMountPoint(Path path) throws IOException {
    Path canonicalPath = realPathNoFollow(path);
    Path parent = canonicalPath.getParent();
    if (parent != null && !sameFileStore(canonicalPath, parent)) {
      return true;
    }
    return mountMetadata.isMountPoint(canonicalPath);
  }

  @Override
  public Object dosAttributesNoFollow(Path path) throws IOException {
    return Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public DirectoryStream<Path> openDirectory(Path path) throws IOException {
    return Files.newDirectoryStream(path);
  }

  @Override
  public InputStream openFileNoFollow(Path path) throws IOException {
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    return Channels.newInputStream(openNoFollowChannel(path, options));
  }

  /** Opens a channel with NOFOLLOW semantics; unsupported providers must fail rather than reopen. */
  protected SeekableByteChannel openNoFollowChannel(Path path, Set<OpenOption> options)
      throws IOException {
    return Files.newByteChannel(path, options);
  }

  private static SharedFolderMountMetadata defaultMountMetadata() {
    String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (operatingSystem.contains("linux")) {
      return new LinuxSharedFolderMountMetadata();
    }
    if (operatingSystem.contains("windows")) {
      return canonicalPath -> false;
    }
    return canonicalPath -> {
      throw new IOException("Mount metadata is unavailable for this filesystem provider");
    };
  }
}

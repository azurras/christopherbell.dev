package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

/** Real NIO-backed implementation of the shared-folder filesystem boundary. */
public class NioSharedFolderFileSystemBoundary implements SharedFolderFileSystemBoundary {
  private static final Path LINUX_MOUNT_INFO = Path.of("/proc/self/mountinfo");

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
    if (parent == null) {
      return false;
    }
    if (!sameFileStore(canonicalPath, parent)) {
      return true;
    }
    return isListedLinuxMount(canonicalPath);
  }

  @Override
  public Object dosAttributesNoFollow(Path path) throws IOException {
    return Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
  }

  private boolean isListedLinuxMount(Path canonicalPath) throws IOException {
    if (!Files.isReadable(LINUX_MOUNT_INFO)) {
      return false;
    }
    try (Stream<String> lines = Files.lines(LINUX_MOUNT_INFO)) {
      return lines
          .map(NioSharedFolderFileSystemBoundary::mountPointFrom)
          .filter(mountPoint -> mountPoint != null)
          .map(Path::of)
          .map(this::absoluteNormalized)
          .anyMatch(canonicalPath::equals);
    }
  }

  private static String mountPointFrom(String mountInfoLine) {
    int separator = mountInfoLine.indexOf(" - ");
    if (separator < 0) {
      return null;
    }
    String[] fields = mountInfoLine.substring(0, separator).split(" ");
    if (fields.length < 5) {
      return null;
    }
    return unescapeMountInfoPath(fields[4]);
  }

  private static String unescapeMountInfoPath(String value) {
    StringBuilder unescaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\\' && index + 3 < value.length()
          && value.charAt(index + 1) >= '0' && value.charAt(index + 1) <= '7'
          && value.charAt(index + 2) >= '0' && value.charAt(index + 2) <= '7'
          && value.charAt(index + 3) >= '0' && value.charAt(index + 3) <= '7') {
        unescaped.append((char) Integer.parseInt(value.substring(index + 1, index + 4), 8));
        index += 3;
      } else {
        unescaped.append(character);
      }
    }
    return unescaped.toString();
  }
}

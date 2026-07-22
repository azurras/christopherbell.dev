package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Linux mount metadata reader backed by {@code /proc/self/mountinfo}. */
public final class LinuxSharedFolderMountMetadata implements SharedFolderMountMetadata {
  private static final Path DEFAULT_MOUNT_INFO = Path.of("/proc/self/mountinfo");

  private final Path mountInfo;

  /** Creates a reader for the current process's Linux mount metadata. */
  public LinuxSharedFolderMountMetadata() {
    this(DEFAULT_MOUNT_INFO);
  }

  /**
   * Creates a reader for the supplied Linux mount metadata source.
   *
   * <p>This supports controlled production filesystem providers that expose mount metadata at a
   * different path.
   *
   * @param mountInfo Linux mountinfo source
   */
  public LinuxSharedFolderMountMetadata(Path mountInfo) {
    if (mountInfo == null) {
      throw new IllegalArgumentException("Linux mount metadata path is required");
    }
    this.mountInfo = mountInfo;
  }

  @Override
  public boolean isMountPoint(Path canonicalPath) throws IOException {
    if (!Files.isRegularFile(mountInfo) || !Files.isReadable(mountInfo)) {
      throw new IOException("Linux mount metadata is unavailable");
    }
    try (Stream<String> lines = Files.lines(mountInfo)) {
      return lines
          .map(this::mountPointFrom)
          .anyMatch(canonicalPath::equals);
    } catch (MountMetadataException exception) {
      throw new IOException("Linux mount metadata is malformed", exception);
    } catch (UncheckedIOException exception) {
      throw exception.getCause();
    }
  }

  private Path mountPointFrom(String mountInfoLine) {
    int separator = mountInfoLine.indexOf(" - ");
    if (separator < 0) {
      throw new MountMetadataException();
    }
    String[] fields = mountInfoLine.substring(0, separator).trim().split("\\s+");
    if (fields.length < 5 || !fields[4].startsWith("/")) {
      throw new MountMetadataException();
    }
    try {
      return Path.of(unescapeMountInfoPath(fields[4])).toAbsolutePath().normalize();
    } catch (InvalidPathException exception) {
      throw new MountMetadataException(exception);
    }
  }

  private String unescapeMountInfoPath(String value) {
    StringBuilder unescaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\\' && index + 3 < value.length()
          && value.charAt(index + 1) >= '0' && value.charAt(index + 1) <= '7'
          && value.charAt(index + 2) >= '0' && value.charAt(index + 2) <= '7'
          && value.charAt(index + 3) >= '0' && value.charAt(index + 3) <= '7') {
        unescaped.append((char) Integer.parseInt(value.substring(index + 1, index + 4), 8));
        index += 3;
      } else if (character == '\\') {
        throw new MountMetadataException();
      } else {
        unescaped.append(character);
      }
    }
    return unescaped.toString();
  }

  private static final class MountMetadataException extends RuntimeException {
    private MountMetadataException() {
      super();
    }

    private MountMetadataException(Throwable cause) {
      super(cause);
    }
  }
}

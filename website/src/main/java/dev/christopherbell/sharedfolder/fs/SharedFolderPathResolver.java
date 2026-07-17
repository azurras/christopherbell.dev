package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves untrusted shared-folder names beneath one configured directory using Windows-safe
 * naming rules.
 *
 * <p>Resolution is not authorization. Callers must make their fresh access decision separately
 * and invoke {@link #recheckForMutation(Path)} immediately before a mutation that follows a
 * previous resolution. That recheck detects a link or reparse-point substitution made between
 * validation and use.
 */
public final class SharedFolderPathResolver {
  private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x0400;
  private static final Set<String> RESERVED_DOS_NAMES = Set.of(
      "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
      "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

  private final Path root;

  /**
   * Creates a resolver for an existing ordinary directory.
   *
   * @param root configured shared-folder root
   * @throws UnsafeSharedPathException when the root is unavailable, not a directory, or a link
   *     or Windows reparse point
   */
  public SharedFolderPathResolver(Path root) {
    if (root == null) {
      throw unsafe("Shared-folder root is required");
    }
    try {
      this.root = root.toAbsolutePath().normalize();
      verifyRoot();
    } catch (IOException | SecurityException exception) {
      if (exception instanceof UnsafeSharedPathException unsafePathException) {
        throw unsafePathException;
      }
      throw unsafe("Shared-folder root cannot be inspected", exception);
    }
  }

  /**
   * Resolves an existing ordinary path strictly beneath the configured root.
   *
   * <p>An empty path selects the root itself. All non-empty input must use forward-slash-separated
   * Windows-safe names; callers cannot supply an absolute, drive-qualified, alternate-stream, or
   * dot-segment path.
   *
   * @param raw untrusted relative path
   * @return the validated absolute path
   * @throws UnsafeSharedPathException when the path is unsafe or does not exist as an ordinary
   *     descendant
   */
  public Path existing(String raw) {
    Path relative = parseRelative(raw, true);
    Path candidate = root.resolve(relative).normalize();
    requireBeneathRoot(candidate);
    verifyExistingChain(candidate);
    return candidate;
  }

  /**
   * Resolves one new safe child name beneath an existing safe parent.
   *
   * <p>The returned child may not exist. Recheck its parent immediately before creating or
   * replacing the child so a path substitution cannot turn this result into an escape.
   *
   * @param parent untrusted existing relative parent path
   * @param name one untrusted Windows-safe child name
   * @return the validated absolute child path
   * @throws UnsafeSharedPathException when either input is unsafe or the parent is unavailable
   */
  public Path newChild(String parent, String name) {
    Path safeParent = existing(parent);
    validateSingleWindowsName(name);
    recheckForMutation(safeParent);
    Path child = safeParent.resolve(name).normalize();
    requireBeneathRoot(child);
    return child;
  }

  /**
   * Revalidates an existing path at the last responsible moment before a filesystem mutation.
   *
   * @param resolved a path previously returned by {@link #existing(String)} or an existing parent
   * @return the revalidated absolute path
   * @throws UnsafeSharedPathException when a segment is missing, linked, or a reparse point
   */
  public Path recheckForMutation(Path resolved) {
    if (resolved == null) {
      throw unsafe("Shared-folder mutation path is required");
    }
    try {
      Path candidate = resolved.toAbsolutePath().normalize();
      requireBeneathRoot(candidate);
      verifyExistingChain(candidate);
      return candidate;
    } catch (SecurityException exception) {
      if (exception instanceof UnsafeSharedPathException unsafePathException) {
        throw unsafePathException;
      }
      throw unsafe("Shared-folder mutation path cannot be inspected", exception);
    }
  }

  private Path parseRelative(String raw, boolean allowEmpty) {
    if (raw == null) {
      throw unsafe("Shared-folder path is required");
    }
    if (raw.isEmpty()) {
      if (allowEmpty) {
        return Path.of("");
      }
      throw unsafe("Shared-folder name is required");
    }
    if (raw.startsWith("/") || raw.startsWith("\\") || raw.matches("(?i)^[a-z]:.*")) {
      throw unsafe("Shared-folder paths must be relative");
    }
    if (raw.indexOf('\\') >= 0 || raw.indexOf(':') >= 0 || containsControlCharacter(raw)
        || containsEncodedSeparator(raw)) {
      throw unsafe("Shared-folder path contains an unsafe Windows form");
    }

    String[] segments = raw.split("/", -1);
    for (String segment : segments) {
      validateSegment(segment);
    }
    try {
      Path relative = Path.of(raw);
      if (relative.isAbsolute() || relative.getRoot() != null) {
        throw unsafe("Shared-folder paths must be relative");
      }
      return relative;
    } catch (SecurityException exception) {
      if (exception instanceof UnsafeSharedPathException unsafePathException) {
        throw unsafePathException;
      }
      throw unsafe("Shared-folder path cannot be parsed", exception);
    }
  }

  private void validateSingleWindowsName(String name) {
    if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      throw unsafe("Shared-folder child name must be one path segment");
    }
    validateSegment(name);
  }

  private void validateSegment(String segment) {
    if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
      throw unsafe("Shared-folder dot and empty path segments are not allowed");
    }
    if (segment.endsWith(".") || segment.endsWith(" ") || containsControlCharacter(segment)
        || segment.indexOf(':') >= 0 || containsEncodedSeparator(segment)
        || containsWindowsForbiddenCharacter(segment)) {
      throw unsafe("Shared-folder path segment is not a safe Windows name");
    }
    String baseName = segment.substring(0, segment.indexOf('.') >= 0 ? segment.indexOf('.') : segment.length())
        .toUpperCase(Locale.ROOT);
    if (RESERVED_DOS_NAMES.contains(baseName)) {
      throw unsafe("Shared-folder path segment uses a reserved DOS device name");
    }
  }

  private void verifyRoot() throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)
        || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw unsafe("Shared-folder root must be an existing directory");
    }
    requireOrdinary(root);
  }

  private void verifyExistingChain(Path candidate) {
    try {
      verifyRoot();
      Path current = root;
      for (Path segment : root.relativize(candidate)) {
        current = current.resolve(segment);
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
          throw unsafe("Shared-folder path does not exist");
        }
        requireOrdinary(current);
      }
    } catch (IOException | SecurityException exception) {
      if (exception instanceof UnsafeSharedPathException unsafePathException) {
        throw unsafePathException;
      }
      throw unsafe("Shared-folder path cannot be inspected", exception);
    }
  }

  private void requireBeneathRoot(Path candidate) {
    if (!candidate.startsWith(root)) {
      throw unsafe("Shared-folder path escapes the configured root");
    }
  }

  private void requireOrdinary(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || attributes.isOther() || hasWindowsReparsePoint(path)) {
      throw unsafe("Shared-folder paths cannot contain links or reparse points");
    }
  }

  private boolean hasWindowsReparsePoint(Path path) throws IOException {
    try {
      Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
      return attributes instanceof Number number
          && (number.intValue() & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    } catch (UnsupportedOperationException exception) {
      return false;
    }
  }

  private boolean containsControlCharacter(String value) {
    return value.codePoints().anyMatch(codePoint -> codePoint <= 0x1f || codePoint == 0x7f);
  }

  private boolean containsEncodedSeparator(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    return normalized.contains("%2f") || normalized.contains("%5c");
  }

  private boolean containsWindowsForbiddenCharacter(String value) {
    return value.indexOf('"') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
        || value.indexOf('|') >= 0 || value.indexOf('?') >= 0 || value.indexOf('*') >= 0;
  }

  private UnsafeSharedPathException unsafe(String message) {
    return new UnsafeSharedPathException(message);
  }

  private UnsafeSharedPathException unsafe(String message, Throwable cause) {
    return new UnsafeSharedPathException(message, cause);
  }
}

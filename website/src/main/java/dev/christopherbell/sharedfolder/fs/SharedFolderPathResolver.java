package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves untrusted shared-folder names beneath one configured directory using Windows-safe
 * naming rules.
 *
 * <p>Resolution is not authorization. Callers must make their fresh access decision separately
 * and invoke {@link #recheckForMutation(Path)} immediately before a mutation that follows a
 * previous resolution. Use {@link #readHandle(Path)} for reads: its open methods recheck the full
 * path chain and captured leaf identity at the last responsible moment before the provider opens
 * the directory or file.
 */
public final class SharedFolderPathResolver {
  private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x0400;
  private static final Set<String> RESERVED_DOS_NAMES = Set.of(
      "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
      "COM8", "COM9", "COM\u00b9", "COM\u00b2", "COM\u00b3", "LPT1", "LPT2", "LPT3", "LPT4",
      "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "LPT\u00b9", "LPT\u00b2", "LPT\u00b3");

  private final SharedFolderFileSystemBoundary fileSystem;
  private final Path root;
  private final Path canonicalRoot;
  private final Object rootFileKey;

  /**
   * Creates a resolver that uses the production NIO filesystem boundary.
   *
   * @param root configured shared-folder root
   */
  public SharedFolderPathResolver(Path root) {
    this(root, new RootedNioSharedFolderFileSystemBoundary(root));
  }

  /**
   * Creates a resolver with an explicit production filesystem boundary.
   *
   * <p>The boundary is injectable so deployments with a controlled filesystem provider retain
   * the same canonical, file-store, mount, and reparse-point contract as the default NIO
   * implementation.
   *
   * @param root configured shared-folder root
   * @param fileSystem filesystem facts used to prove every existing segment
   * @throws UnsafeSharedPathException when the root is unavailable, not a directory, linked,
   *     reparse-pointed, mounted, or not canonically stable
   */
  public SharedFolderPathResolver(Path root, SharedFolderFileSystemBoundary fileSystem) {
    if (root == null || fileSystem == null) {
      throw unsafe("Shared-folder root and filesystem boundary are required");
    }
    this.fileSystem = fileSystem;
    try {
      this.root = fileSystem.absoluteNormalized(root);
      ExistingIdentity rootIdentity = inspectExistingSegment(this.root, true);
      this.canonicalRoot = rootIdentity.canonicalPath();
      this.rootFileKey = rootIdentity.fileKey();
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
   * @throws UnsafeSharedPathException when a segment is missing, linked, reparse-pointed, mounted,
   *     or no longer canonically beneath the configured root
   */
  public Path recheckForMutation(Path resolved) {
    return recheckExisting(resolved, "mutation");
  }

  /**
   * Revalidates an existing path immediately before a read operation.
   *
   * @param resolved a path previously returned by {@link #existing(String)}
   * @return the revalidated absolute path
   */
  public Path recheckForRead(Path resolved) {
    return recheckExisting(resolved, "read");
  }

  /**
   * Captures the ordinary leaf identity that a later read must still match.
   *
   * <p>The returned handle never exposes an absolute path. Callers must use its attribute or open
   * methods rather than reopening the earlier pathname themselves.
   *
   * @param resolved a path previously returned by {@link #existing(String)}
   * @return a revalidating read handle
   */
  public ReadHandle readHandle(Path resolved) {
    Path candidate = recheckForRead(resolved);
    try {
      return new ReadHandle(candidate, inspectExistingSegment(candidate, candidate.equals(root)));
    } catch (IOException | SecurityException exception) {
      if (exception instanceof UnsafeSharedPathException unsafePathException) {
        throw unsafePathException;
      }
      throw unsafe("Shared-folder read path cannot be inspected", exception);
    }
  }

  private Path recheckExisting(Path resolved, String operation) {
    if (resolved == null) {
      throw unsafe("Shared-folder " + operation + " path is required");
    }
    try {
      Path candidate = fileSystem.absoluteNormalized(resolved);
      requireBeneathRoot(candidate);
      verifyExistingChain(candidate);
      return candidate;
    } catch (SecurityException exception) {
      if (exception instanceof UnsafeSharedPathException unsafePathException) {
        throw unsafePathException;
      }
      throw unsafe("Shared-folder " + operation + " path cannot be inspected", exception);
    }
  }

  private Path parseRelative(String raw, boolean allowEmpty) {
    List<String> segments = safeRelativeSegments(raw, allowEmpty);
    if (segments.isEmpty()) {
      return root.getFileSystem().getPath("");
    }
    try {
      Path relative = root.getFileSystem().getPath(raw);
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

  /**
   * Parses untrusted path text into Windows-safe relative components without touching a filesystem.
   *
   * <p>The native held-root boundary uses exactly this grammar before issuing each handle-relative
   * open, so native directory enumeration and NIO resolution cannot drift apart.
   */
  public static List<String> safeRelativeSegments(String raw, boolean allowEmpty) {
    if (raw == null) {
      throw unsafe("Shared-folder path is required");
    }
    if (raw.isEmpty()) {
      if (allowEmpty) {
      return List.of();
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
    return List.of(segments);
  }

  /** Validates one name returned from native enumeration before exposing it to callers. */
  public static void validateSingleWindowsName(String name) {
    if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      throw unsafe("Shared-folder child name must be one path segment");
    }
    validateSegment(name);
  }

  private static void validateSegment(String segment) {
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

  private ExistingIdentity verifyRoot() throws IOException {
    ExistingIdentity rootIdentity = inspectExistingSegment(root, true);
    if (!sameRootIdentity(rootIdentity)) {
      throw unsafe("Shared-folder root canonical identity changed");
    }
    return rootIdentity;
  }

  private void verifyExistingChain(Path candidate) {
    try {
      verifyRoot();
      Path current = root;
      for (Path segment : root.relativize(candidate)) {
        current = current.resolve(segment);
        ExistingIdentity identity = inspectExistingSegment(current, false);
        if (!identity.canonicalPath().startsWith(canonicalRoot)) {
          throw unsafe("Shared-folder path canonical identity escapes the configured root");
        }
        if (!fileSystem.sameFileStore(root, current)) {
          throw unsafe("Shared-folder paths cannot cross a filesystem boundary");
        }
      }
    } catch (IOException | SecurityException exception) {
      if (exception instanceof UnsafeSharedPathException unsafePathException) {
        throw unsafePathException;
      }
      throw unsafe("Shared-folder path cannot be inspected", exception);
    }
  }

  private ExistingIdentity inspectExistingSegment(Path path, boolean rootPath) throws IOException {
    if (!fileSystem.existsNoFollow(path)) {
      throw unsafe(rootPath ? "Shared-folder root must exist" : "Shared-folder path does not exist");
    }
    BasicFileAttributes attributes = fileSystem.readAttributesNoFollow(path);
    if (rootPath && !attributes.isDirectory()) {
      throw unsafe("Shared-folder root must be an existing directory");
    }
    requireOrdinary(path, attributes);
    if (fileSystem.isMountPoint(path)) {
      throw unsafe("Shared-folder paths cannot contain filesystem mount points");
    }
    Path canonicalPath = fileSystem.realPath(path);
    return new ExistingIdentity(canonicalPath, attributes.fileKey(), attributes);
  }

  private boolean sameRootIdentity(ExistingIdentity currentRoot) {
    if (!canonicalRoot.equals(currentRoot.canonicalPath())) {
      return false;
    }
    return rootFileKey == null || currentRoot.fileKey() == null
        || rootFileKey.equals(currentRoot.fileKey());
  }

  private void requireBeneathRoot(Path candidate) {
    if (!candidate.startsWith(root)) {
      throw unsafe("Shared-folder path escapes the configured root");
    }
  }

  private void requireOrdinary(Path path, BasicFileAttributes attributes) throws IOException {
    if (attributes.isSymbolicLink() || attributes.isOther() || hasWindowsReparsePoint(path)) {
      throw unsafe("Shared-folder paths cannot contain links or reparse points");
    }
  }

  private boolean hasWindowsReparsePoint(Path path) throws IOException {
    try {
      Object attributes = fileSystem.dosAttributesNoFollow(path);
      return attributes instanceof Number number
          && (number.intValue() & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    } catch (UnsupportedOperationException | IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean containsControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  private static boolean containsEncodedSeparator(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    return normalized.contains("%2f") || normalized.contains("%5c");
  }

  private static boolean containsWindowsForbiddenCharacter(String value) {
    return value.indexOf('"') >= 0 || value.indexOf('<') >= 0 || value.indexOf('>') >= 0
        || value.indexOf('|') >= 0 || value.indexOf('?') >= 0 || value.indexOf('*') >= 0;
  }

  private static UnsafeSharedPathException unsafe(String message) {
    return new UnsafeSharedPathException(message);
  }

  private static UnsafeSharedPathException unsafe(String message, Throwable cause) {
    return new UnsafeSharedPathException(message, cause);
  }

  /**
   * A leaf path that is rechecked immediately before metadata reads and provider opens.
   *
   * <p>The handle is intentionally pathless to its callers. This prevents a service from
   * validating a path once and later passing that mutable path to a {@code Resource}.
   */
  public final class ReadHandle {
    private final Path selectedPath;
    private final ExistingIdentity expectedIdentity;

    private ReadHandle(Path selectedPath, ExistingIdentity expectedIdentity) {
      this.selectedPath = selectedPath;
      this.expectedIdentity = expectedIdentity;
    }

    /** Rechecks the chain and leaf identity immediately before returning safe metadata. */
    public BasicFileAttributes attributes() {
      return verifyCurrentIdentity().attributes();
    }

    /** Rechecks the chain and leaf identity immediately before opening a directory stream. */
    public DirectoryStream<Path> openDirectory() throws IOException {
      ExistingIdentity current = verifyCurrentIdentity();
      if (!current.attributes().isDirectory()) {
        throw unsafe("Shared-folder directory is no longer available");
      }
      return fileSystem.openDirectory(selectedPath);
    }

    /** Rechecks the chain and leaf identity immediately before opening an ordinary input stream. */
    public InputStream openFile() throws IOException {
      ExistingIdentity current = verifyCurrentIdentity();
      if (!current.attributes().isRegularFile()) {
        throw unsafe("Shared-folder file is no longer available");
      }
      try {
        return fileSystem.openFileNoFollow(selectedPath);
      } catch (UnsupportedOperationException exception) {
        throw unsafe("Shared-folder provider cannot safely open files", exception);
      }
    }

    private ExistingIdentity verifyCurrentIdentity() {
      Path candidate = recheckForRead(selectedPath);
      try {
        ExistingIdentity current = inspectExistingSegment(candidate, candidate.equals(root));
        if (!sameLeafIdentity(expectedIdentity, current)) {
          throw unsafe("Shared-folder read target identity changed");
        }
        return current;
      } catch (IOException | SecurityException exception) {
        if (exception instanceof UnsafeSharedPathException unsafePathException) {
          throw unsafePathException;
        }
        throw unsafe("Shared-folder read target cannot be inspected", exception);
      }
    }
  }

  private boolean sameLeafIdentity(ExistingIdentity expected, ExistingIdentity current) {
    if (!expected.canonicalPath().equals(current.canonicalPath())) {
      return false;
    }
    if (expected.fileKey() != null && current.fileKey() != null) {
      return expected.fileKey().equals(current.fileKey());
    }
    BasicFileAttributes expectedAttributes = expected.attributes();
    BasicFileAttributes currentAttributes = current.attributes();
    return expectedAttributes.isDirectory() == currentAttributes.isDirectory()
        && expectedAttributes.isRegularFile() == currentAttributes.isRegularFile()
        && expectedAttributes.size() == currentAttributes.size()
        && expectedAttributes.lastModifiedTime().equals(currentAttributes.lastModifiedTime())
        && expectedAttributes.creationTime().equals(currentAttributes.creationTime());
  }

  private record ExistingIdentity(
      Path canonicalPath, Object fileKey, BasicFileAttributes attributes) {}
}

package dev.christopherbell.sharedfolder.fs;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves private upload and recovery files beneath one pre-created, ordinary system root. */
public final class PortableSharedFolderPrivateBoundary {
  private final Path configuredRoot;
  private final SharedFolderFileSystemBoundary fileSystem;
  private final List<Identity> configuredChain;
  private final IOException initializationFailure;
  private final Map<String, Identity> directories = new ConcurrentHashMap<>();

  public PortableSharedFolderPrivateBoundary(Path configuredRoot) {
    this(configuredRoot, new NioSharedFolderFileSystemBoundary());
  }

  public PortableSharedFolderPrivateBoundary(
      Path configuredRoot, SharedFolderFileSystemBoundary fileSystem) {
    if (configuredRoot == null) throw new IllegalArgumentException("system root is required");
    this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
    this.fileSystem = java.util.Objects.requireNonNull(fileSystem);
    List<Identity> captured = List.of();
    IOException failure = null;
    try {
      captured = captureChain(this.configuredRoot);
      Identity root = captured.get(captured.size() - 1);
      if (!root.attributes().isDirectory() || root.mountPoint()) {
        throw new IOException("pre-created private shared-folder system root is not a directory");
      }
    } catch (IOException | SecurityException exception) {
      failure = exception instanceof IOException io ? io
          : new IOException("private shared-folder root cannot be inspected", exception);
    }
    this.configuredChain = captured;
    this.initializationFailure = failure;
  }

  /** Returns one ordinary private child directory, creating only that direct child if absent. */
  Path directory(String name) throws IOException {
    validateName(name);
    Path root = verifiedRoot();
    Path child = root.resolve(name);
    try {
      Files.createDirectory(child);
    } catch (FileAlreadyExistsException ignored) {
      // Revalidated below without following links.
    }
    FileStore rootStore = Files.getFileStore(root);
    FileStore childStore = Files.getFileStore(child);
    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)
        || !rootStore.name().equals(childStore.name())
        || !rootStore.type().equals(childStore.type())
        || !child.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root)) {
      throw new IOException("private shared-folder directory is unsafe");
    }
    Identity observed = identity(child);
    if (observed.mountPoint()) {
      throw new IOException("private shared-folder directory is a filesystem mount");
    }
    Identity expected = directories.putIfAbsent(name, observed);
    if (expected != null && !expected.matches(observed)) {
      throw new IOException("private shared-folder directory identity changed");
    }
    verifyChain();
    return child;
  }

  /** Returns one contained private leaf; the leaf itself may be absent. */
  private Path file(String directory, String key) throws IOException {
    validateName(key);
    Path parent = directory(directory);
    Path leaf = parent.resolve(key).normalize();
    if (!leaf.getParent().equals(parent)) throw new IOException("private shared-folder key escaped");
    return leaf;
  }

  /** Runs one private-leaf operation between full ancestor and child identity checks. */
  public <T> T operateOnFile(
      String directory, String key, CheckedPathOperation<T> operation) throws IOException {
    Path path;
    try {
      path = file(directory, key);
    } catch (IOException | SecurityException exception) {
      throw unavailable(exception);
    }
    return operate(path, operation);
  }

  /** Runs one private-directory operation between full ancestor and child identity checks. */
  public <T> T operateOnDirectory(String directory, CheckedPathOperation<T> operation)
      throws IOException {
    Path path;
    try {
      path = directory(directory);
    } catch (IOException | SecurityException exception) {
      throw unavailable(exception);
    }
    return operate(path, operation);
  }

  private <T> T operate(Path path, CheckedPathOperation<T> operation) throws IOException {
    Objects.requireNonNull(operation, "private shared-folder operation is required");
    verifyForOperation();
    try {
      T result = operation.apply(path);
      verifyForOperation();
      return result;
    } catch (IOException | RuntimeException | Error failure) {
      try {
        verifyForOperation();
      } catch (BoundaryUnavailableException verificationFailure) {
        verificationFailure.addSuppressed(failure);
        throw verificationFailure;
      }
      throw failure;
    }
  }

  private void verifyForOperation() throws BoundaryUnavailableException {
    try {
      verify();
    } catch (IOException | SecurityException exception) {
      throw unavailable(exception);
    }
  }

  private BoundaryUnavailableException unavailable(Exception cause) {
    return cause instanceof BoundaryUnavailableException boundaryFailure
        ? boundaryFailure
        : new BoundaryUnavailableException(cause);
  }

  /** Checked operation used only while a verified private capability is active. */
  @FunctionalInterface
  public interface CheckedPathOperation<T> {
    T apply(Path path) throws IOException;
  }

  /** Signals that private containment could not be proven before or after an operation. */
  public static final class BoundaryUnavailableException extends IOException {
    private BoundaryUnavailableException(Exception cause) {
      super("private shared-folder boundary is unavailable", cause);
    }
  }

  private Path verifiedRoot() throws IOException {
    verifyChain();
    return configuredChain.get(configuredChain.size() - 1).canonicalPath();
  }

  private void verifyChain() throws IOException {
    if (initializationFailure != null) throw initializationFailure;
    for (Identity expected : configuredChain) {
      Identity current = identity(expected.path());
      if (!expected.matches(current)) {
        throw new IOException("private shared-folder ancestor identity changed");
      }
    }
  }

  /** Rechecks the captured ancestor/root and private-directory identities after a leaf operation. */
  void verify() throws IOException {
    verifyChain();
    for (Map.Entry<String, Identity> entry : directories.entrySet()) {
      Identity current = identity(entry.getValue().path());
      if (!entry.getValue().matches(current)) {
        throw new IOException("private shared-folder directory identity changed");
      }
    }
  }

  private List<Identity> captureChain(Path leaf) throws IOException {
    List<Path> paths = new ArrayList<>();
    for (Path current = leaf; current != null; current = current.getParent()) paths.add(0, current);
    List<Identity> identities = new ArrayList<>();
    for (Path path : paths) identities.add(identity(path));
    return List.copyOf(identities);
  }

  private Identity identity(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || attributes.isOther() || isWindowsReparsePoint(path)) {
      throw new IOException("private shared-folder chain contains a link or reparse point");
    }
    if (!"file".equalsIgnoreCase(path.getFileSystem().provider().getScheme())) {
      throw new IOException("private shared-folder provider is unsupported");
    }
    Path canonicalPath = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
    Object stableIdentity = attributes.fileKey() == null
        ? canonicalPath + "@" + attributes.creationTime()
        : attributes.fileKey();
    FileStore store = Files.getFileStore(path);
    boolean mountPoint = fileSystem.isMountPoint(path);
    return new Identity(path, canonicalPath, stableIdentity,
        store.name(), store.type(), mountPoint, attributes);
  }

  private boolean isWindowsReparsePoint(Path path) throws IOException {
    try {
      Object value = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
      return value instanceof Number number && (number.intValue() & 0x0400) != 0;
    } catch (UnsupportedOperationException exception) {
      return false;
    }
  }

  private void validateName(String name) throws IOException {
    if (name == null || name.isBlank() || name.length() > 255
        || !name.matches("[A-Za-z0-9._-]+") || name.equals(".") || name.equals("..")) {
      throw new IOException("invalid private shared-folder name");
    }
  }

  private record Identity(
      Path path, Path canonicalPath, Object fileKey, String fileStoreName, String fileStoreType,
      boolean mountPoint, BasicFileAttributes attributes) {
    private boolean matches(Identity other) {
      return canonicalPath.equals(other.canonicalPath)
          && fileKey.equals(other.fileKey)
          && fileStoreName.equals(other.fileStoreName)
          && fileStoreType.equals(other.fileStoreType)
          && mountPoint == other.mountPoint
          && attributes.isDirectory() == other.attributes.isDirectory();
    }
  }
}

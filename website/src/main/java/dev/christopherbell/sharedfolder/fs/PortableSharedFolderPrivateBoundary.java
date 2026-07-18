package dev.christopherbell.sharedfolder.fs;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileStore;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

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

  /** Opens one ordinary private regular file without following links and retains its channel. */
  public <T> T operateOnRegularFile(
      String directory, String key, FileAccess access, CheckedFileChannelOperation<T> operation)
      throws IOException {
    Objects.requireNonNull(access, "private file access is required");
    Objects.requireNonNull(operation, "private file operation is required");
    Path path;
    try {
      path = verifiedFile(directory, key);
      verifyForOperation();
    } catch (IOException | SecurityException exception) {
      throw unavailable(exception);
    }
    LeafIdentity expected;
    try {
      expected = leafIdentity(path, true);
    } catch (NoSuchFileException exception) {
      expected = null;
    }
    if (access == FileAccess.CREATE_NEW && expected != null) {
      throw new FileAlreadyExistsException(path.toString());
    }
    Set<OpenOption> options = switch (access) {
      case CREATE_NEW -> Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);
      case READ -> Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      case APPEND_OR_CREATE -> Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);
      case WRITE -> Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    };
    try (FileChannel channel = FileChannel.open(path, options);
        FileLock lock = access.writes() ? exclusiveLock(channel) : null) {
      LeafIdentity opened = leafIdentity(path, true);
      if (expected != null && !expected.sameObject(opened)) {
        throw unavailable(new IOException("private shared-folder leaf identity changed"));
      }
      if (access == FileAccess.APPEND_OR_CREATE) channel.position(channel.size());
      T result = operation.apply(channel);
      LeafIdentity current = leafIdentity(path, true);
      if (!opened.sameObject(current)) {
        throw unavailable(new IOException("private shared-folder leaf identity changed"));
      }
      verifyForOperation();
      return result;
    } catch (BoundaryUnavailableException exception) {
      throw exception;
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

  /** Returns a stable, no-follow metadata snapshot for one ordinary private leaf. */
  public PrivateLeafMetadata metadata(String directory, String key) throws IOException {
    try {
      Path path = verifiedFile(directory, key);
      verifyForOperation();
      LeafIdentity before = leafIdentity(path, false);
      verifyForOperation();
      LeafIdentity after = leafIdentity(path, false);
      if (!before.sameObject(after)) {
        throw unavailable(new IOException("private shared-folder leaf identity changed"));
      }
      return new PrivateLeafMetadata(before.attributes(), before.stableIdentity());
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Returns false only when the named private leaf is definitely absent. */
  public boolean exists(String directory, String key) throws IOException {
    try {
      metadata(directory, key);
      return true;
    } catch (NoSuchFileException exception) {
      return false;
    }
  }

  /** Checks one verified private directory leaf without handing its path to callers. */
  public boolean directoryIsEmpty(String directory, String key) throws IOException {
    try {
      Path path = verifiedFile(directory, key);
      verifyForOperation();
      LeafIdentity before = leafIdentity(path, false);
      if (!before.attributes().isDirectory()) {
        throw unavailable(new IOException("private shared-folder leaf is not a directory"));
      }
      boolean empty;
      try (var children = Files.list(path)) {
        empty = children.findAny().isEmpty();
      }
      LeafIdentity after = leafIdentity(path, false);
      if (!before.sameObject(after)) {
        throw unavailable(new IOException("private shared-folder leaf identity changed"));
      }
      verifyForOperation();
      return empty;
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Returns usable bytes for one verified private child directory. */
  public long usableSpace(String directory) throws IOException {
    try {
      Path path = verifiedDirectory(directory);
      verifyForOperation();
      long usable = Files.getFileStore(path).getUsableSpace();
      verifyForOperation();
      return usable;
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Proves a verified private child directory shares a file store with another path. */
  public boolean directorySharesFileStore(String directory, Path other) throws IOException {
    Objects.requireNonNull(other, "comparison path is required");
    try {
      Path path = verifiedDirectory(directory);
      verifyForOperation();
      boolean same = Files.getFileStore(path).equals(Files.getFileStore(other));
      verifyForOperation();
      return same;
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Proves two verified private child directories share a file store. */
  public boolean directoriesShareFileStore(String first, String second) throws IOException {
    try {
      Path firstPath = verifiedDirectory(first);
      Path secondPath = verifiedDirectory(second);
      verifyForOperation();
      boolean same = Files.getFileStore(firstPath).equals(Files.getFileStore(secondPath));
      verifyForOperation();
      return same;
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Moves one ordinary visible leaf into a definitely absent private name. */
  public void moveInto(String directory, String key, Path source) throws IOException {
    Objects.requireNonNull(source, "move source is required");
    try {
      Path target = verifiedFile(directory, key);
      verifyForOperation();
      LeafIdentity sourceIdentity = leafIdentity(source, false);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new FileAlreadyExistsException(target.toString());
      }
      Files.move(source, target);
      LeafIdentity movedIdentity = leafIdentity(target, false);
      if (!sourceIdentity.sameObject(movedIdentity)) {
        throw unavailable(new IOException("private shared-folder moved leaf identity changed"));
      }
      verifyForOperation();
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Moves one verified private leaf to a definitely absent visible name. */
  public void moveOut(String directory, String key, Path target) throws IOException {
    Objects.requireNonNull(target, "move target is required");
    try {
      Path source = verifiedFile(directory, key);
      verifyForOperation();
      LeafIdentity sourceIdentity = leafIdentity(source, false);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new FileAlreadyExistsException(target.toString());
      }
      Files.move(source, target);
      LeafIdentity movedIdentity = leafIdentity(target, false);
      if (!sourceIdentity.sameObject(movedIdentity)) {
        throw unavailable(new IOException("private shared-folder moved leaf identity changed"));
      }
      verifyForOperation();
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Deletes one verified private leaf without following it. */
  public boolean deleteIfExists(String directory, String key) throws IOException {
    try {
      Path path = verifiedFile(directory, key);
      verifyForOperation();
      try {
        leafIdentity(path, false);
      } catch (NoSuchFileException exception) {
        return false;
      }
      Files.delete(path);
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        throw unavailable(new IOException("private shared-folder leaf remained after deletion"));
      }
      verifyForOperation();
      return true;
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Deletes one verified private leaf and fails when it is absent. */
  public void delete(String directory, String key) throws IOException {
    if (!deleteIfExists(directory, key)) {
      throw new NoSuchFileException(key);
    }
  }

  private Path verifiedFile(String directory, String key) throws BoundaryUnavailableException {
    try {
      return file(directory, key);
    } catch (IOException | SecurityException exception) {
      throw unavailable(exception);
    }
  }

  private Path verifiedDirectory(String directory) throws BoundaryUnavailableException {
    try {
      return directory(directory);
    } catch (IOException | SecurityException exception) {
      throw unavailable(exception);
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

  public enum FileAccess {
    CREATE_NEW(true), READ(false), APPEND_OR_CREATE(true), WRITE(true);
    private final boolean writes;
    FileAccess(boolean writes) { this.writes = writes; }
    boolean writes() { return writes; }
  }

  @FunctionalInterface
  public interface CheckedFileChannelOperation<T> {
    T apply(FileChannel channel) throws IOException;
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

  private FileLock exclusiveLock(FileChannel channel) throws IOException {
    FileLock lock = channel.tryLock();
    if (lock == null) throw new IOException("private shared-folder file is already being written");
    return lock;
  }

  private LeafIdentity leafIdentity(Path path, boolean regularOnly) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if ((!attributes.isRegularFile() && (regularOnly || !attributes.isDirectory()))
        || attributes.isSymbolicLink()
        || attributes.isOther() || isWindowsReparsePoint(path)) {
      throw unavailable(new IOException("private shared-folder leaf is unsafe"));
    }
    Object stableIdentity;
    int linkCount;
    if (isWindows()) {
      WindowsLeafFacts facts = windowsLeafFacts(path);
      stableIdentity = facts.identity();
      linkCount = facts.linkCount();
    } else {
      stableIdentity = attributes.fileKey();
      if (stableIdentity == null) {
        throw unavailable(new IOException("private provider has no stable leaf identity"));
      }
      try {
        linkCount = ((Number) Files.getAttribute(
            path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue();
      } catch (UnsupportedOperationException exception) {
        throw unavailable(new IOException("private provider has no link-count support", exception));
      }
    }
    if (attributes.isRegularFile() && linkCount != 1) {
      throw unavailable(new IOException("private shared-folder leaf has multiple links"));
    }
    return new LeafIdentity(stableIdentity, attributes);
  }

  private WindowsLeafFacts windowsLeafFacts(Path path) throws IOException {
    HANDLE handle = Kernel32.INSTANCE.CreateFile(path.toString(), WinNT.FILE_READ_ATTRIBUTES,
        WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
        null, WinNT.OPEN_EXISTING,
        WinNT.FILE_FLAG_OPEN_REPARSE_POINT | WinNT.FILE_FLAG_BACKUP_SEMANTICS, null);
    if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
      throw new IOException("private Windows leaf cannot be opened");
    }
    try {
      WinBase.FILE_ID_INFO id = new WinBase.FILE_ID_INFO();
      if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
          handle, WinBase.FileIdInfo, id.getPointer(), new DWORD(id.size()))) {
        throw new IOException("private Windows leaf identity is unavailable");
      }
      id.read();
      WinBase.FILE_STANDARD_INFO standard = new WinBase.FILE_STANDARD_INFO();
      if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
          handle, WinBase.FileStandardInfo, standard.getPointer(), new DWORD(standard.size()))) {
        throw new IOException("private Windows leaf link count is unavailable");
      }
      standard.read();
      byte[] identifier = new byte[id.FileId.Identifier.length];
      for (int index = 0; index < identifier.length; index++) {
        identifier[index] = id.FileId.Identifier[index].byteValue();
      }
      String stableIdentity = Long.toUnsignedString(id.VolumeSerialNumber) + ":"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(identifier);
      return new WindowsLeafFacts(stableIdentity, standard.NumberOfLinks);
    } finally {
      Kernel32.INSTANCE.CloseHandle(handle);
    }
  }

  private boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
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

  public record PrivateLeafMetadata(BasicFileAttributes attributes, Object stableIdentity) { }

  private record LeafIdentity(Object stableIdentity, BasicFileAttributes attributes) {
    boolean sameObject(LeafIdentity other) {
      return stableIdentity.equals(other.stableIdentity)
          && attributes.isRegularFile() == other.attributes.isRegularFile()
          && attributes.isDirectory() == other.attributes.isDirectory();
    }
  }

  private record WindowsLeafFacts(Object identity, int linkCount) { }
}

package dev.christopherbell.sharedfolder.fs;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.MappedByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.DirectoryStream;
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
public class PortableSharedFolderPrivateBoundary {
  private final Path configuredRoot;
  private final SharedFolderFileSystemBoundary fileSystem;
  private final List<Identity> configuredChain;
  private final IOException initializationFailure;
  private final Map<String, Identity> directories = new ConcurrentHashMap<>();
  private final boolean testOnlyPathMoves;

  public PortableSharedFolderPrivateBoundary(Path configuredRoot) {
    this(configuredRoot, new NioSharedFolderFileSystemBoundary(), false);
  }

  public PortableSharedFolderPrivateBoundary(
      Path configuredRoot, SharedFolderFileSystemBoundary fileSystem) {
    this(configuredRoot, fileSystem, false);
  }

  /** Test-only legacy provider used to exercise durable state machines without production writes. */
  public static PortableSharedFolderPrivateBoundary testOnlyWithPathMoves(Path configuredRoot) {
    return new PortableSharedFolderPrivateBoundary(
        configuredRoot, new NioSharedFolderFileSystemBoundary(), true);
  }

  private PortableSharedFolderPrivateBoundary(
      Path configuredRoot, SharedFolderFileSystemBoundary fileSystem, boolean testOnlyPathMoves) {
    if (configuredRoot == null) throw new IllegalArgumentException("system root is required");
    this.configuredRoot = configuredRoot.toAbsolutePath().normalize();
    this.fileSystem = java.util.Objects.requireNonNull(fileSystem);
    this.testOnlyPathMoves = testOnlyPathMoves;
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
    if (expected == null && (access == FileAccess.READ || access == FileAccess.WRITE)) {
      throw new NoSuchFileException(path.toString());
    }
    if (access == FileAccess.CREATE_NEW) {
      if (expected != null) throw new FileAlreadyExistsException(path.toString());
      Files.createFile(path);
      expected = leafIdentity(path, true);
    } else if (access == FileAccess.APPEND_OR_CREATE && expected == null) {
      Files.createFile(path);
      expected = leafIdentity(path, true);
    }
    if (isWindows()) {
      return operateOnWindowsRegularFile(path, expected, access, operation);
    }
    Set<OpenOption> options = switch (access) {
      case CREATE_NEW -> Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      case READ -> Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      case APPEND_OR_CREATE -> Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
      case WRITE -> Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    };
    try {
      try {
        beforePrivateFileChannelOpen(path, access);
      } catch (IOException exception) {
        throw unavailable(exception);
      }
      try (FileChannel channel = FileChannel.open(path, options);
          FileLock lock = access.writes() ? exclusiveLock(channel) : null) {
        afterPrivateFileOpenBeforeIdentity(path, access);
        LeafIdentity opened = leafIdentity(path, true);
        if (!expected.sameObject(opened)) {
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
      }
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

  private <T> T operateOnWindowsRegularFile(
      Path path, LeafIdentity expected, FileAccess access,
      CheckedFileChannelOperation<T> operation) throws IOException {
    JnaWindowsSharedFolderNativeBridge bridge = new JnaWindowsSharedFolderNativeBridge();
    WindowsSharedFolderNativeBridge.NativeHandle parent = null;
    WindowsSharedFolderNativeBridge.NativeHandle leaf = null;
    try {
      parent = bridge.openRootForMutation(
          path.getParent(), WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE);
      leaf = access == FileAccess.READ
          ? bridge.openRelative(parent, path.getFileName().toString(),
              WindowsSharedFolderNativeBridge.OpenKind.FILE,
              WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE)
          : bridge.openRelativeForExclusiveMutation(parent, path.getFileName().toString(),
              WindowsSharedFolderNativeBridge.OpenKind.FILE,
              WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE);
      var metadata = bridge.metadata(leaf);
      String retainedIdentity = Long.toUnsignedString(metadata.identity().volumeSerial()) + ":"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(metadata.identity().fileId());
      if (!metadata.regularFile() || !expected.stableIdentity().equals(retainedIdentity)) {
        throw unavailable(new IOException("private Windows leaf identity changed"));
      }
      try {
        beforePrivateFileChannelOpen(path, access);
        afterPrivateFileOpenBeforeIdentity(path, access);
      } catch (IOException exception) {
        throw unavailable(exception);
      }
      try (NativeFileChannel channel = new NativeFileChannel(bridge, leaf)) {
        leaf = null;
        if (access == FileAccess.APPEND_OR_CREATE) channel.position(channel.size());
        T result = operation.apply(channel);
        LeafIdentity current = leafIdentity(path, true);
        if (!expected.sameObject(current)) {
          throw unavailable(new IOException("private shared-folder leaf identity changed"));
        }
        verifyForOperation();
        return result;
      }
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (java.nio.file.FileSystemException failure) {
      throw unavailable(failure);
    } catch (IOException failure) {
      throw failure;
    } catch (WindowsSharedFolderNativeBridge.NativeBoundaryException failure) {
      throw unavailable(new IOException("private Windows leaf operation failed", failure));
    } catch (RuntimeException failure) {
      throw failure;
    } finally {
      if (leaf != null) bridge.close(leaf);
      if (parent != null) bridge.close(parent);
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
    if (!testOnlyPathMoves) {
      throw unavailable(new IOException(
          "portable private moves require a retained provider mutation capability"));
    }
    Path target = verifiedFile(directory, key);
    verifyForOperation();
    LeafIdentity sourceIdentity = leafIdentity(source, false);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    Files.move(source, target);
    verifyMovedIdentity(target, sourceIdentity);
    verifyForOperation();
  }

  /** Moves one verified private leaf to a definitely absent visible name. */
  public void moveOut(String directory, String key, Path target) throws IOException {
    Objects.requireNonNull(target, "move target is required");
    if (!testOnlyPathMoves) {
      throw unavailable(new IOException(
          "portable private moves require a retained provider mutation capability"));
    }
    Path source = verifiedFile(directory, key);
    verifyForOperation();
    LeafIdentity sourceIdentity = leafIdentity(source, false);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    Files.move(source, target);
    verifyMovedIdentity(target, sourceIdentity);
    verifyForOperation();
  }

  /** Test seam after a private channel opens but before its named leaf is re-read. */
  protected void afterPrivateFileOpenBeforeIdentity(Path path, FileAccess access) throws IOException { }

  /** Test seam after the retained Windows delete-denial guard and before the Java channel opens. */
  protected void beforePrivateFileChannelOpen(Path path, FileAccess access) throws IOException { }

  private void verifyMovedIdentity(Path target, LeafIdentity sourceIdentity) throws IOException {
    LeafIdentity movedIdentity = leafIdentity(target, false);
    if (!sourceIdentity.sameObject(movedIdentity)) {
      throw unavailable(new IOException("private shared-folder moved leaf identity changed"));
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

  /** Recursively deletes one verified private tree without following links or reparse points. */
  public boolean deleteTreeIfExists(String directory, String key) throws IOException {
    try {
      Path path = verifiedFile(directory, key);
      verifyForOperation();
      final LeafIdentity expected;
      try {
        expected = leafIdentity(path, false);
      } catch (NoSuchFileException exception) {
        return false;
      }
      deleteVerifiedTree(path, expected);
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        throw unavailable(new IOException("private shared-folder tree remained after deletion"));
      }
      verifyForOperation();
      return true;
    } catch (BoundaryUnavailableException exception) {
      throw exception;
    } catch (SecurityException exception) {
      throw unavailable(exception);
    }
  }

  /** Recursively deletes one verified private tree and fails when it is absent. */
  public void deleteTree(String directory, String key) throws IOException {
    if (!deleteTreeIfExists(directory, key)) {
      throw new NoSuchFileException(key);
    }
  }

  private void deleteVerifiedTree(Path path, LeafIdentity expected) throws IOException {
    LeafIdentity current = leafIdentity(path, false);
    if (!expected.sameObject(current)) {
      throw unavailable(new IOException("private shared-folder tree identity changed"));
    }
    if (current.attributes().isDirectory()) {
      try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
        for (Path child : children) {
          LeafIdentity childIdentity = leafIdentity(child, false);
          deleteVerifiedTree(child, childIdentity);
        }
      }
      current = leafIdentity(path, false);
      if (!expected.sameObject(current)) {
        throw unavailable(new IOException("private shared-folder directory identity changed"));
      }
    }
    Files.delete(path);
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
    Object stableIdentity = stableIdentity(path, attributes);
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

  /** Returns a provider-backed stable identity or fails closed when none is available. */
  public static Object stableIdentity(Path path) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return stableIdentity(path, attributes);
  }

  private static Object stableIdentity(Path path, BasicFileAttributes attributes)
      throws IOException {
    if (isWindows()) return windowsLeafFacts(path).identity();
    if (attributes.fileKey() == null) {
      throw new IOException("private shared-folder provider has no stable identity");
    }
    return attributes.fileKey();
  }

  private static WindowsLeafFacts windowsLeafFacts(Path path) throws IOException {
    HANDLE handle = Kernel32.INSTANCE.CreateFile(
        path.toString(), WinNT.FILE_READ_ATTRIBUTES,
        WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
        null, WinNT.OPEN_EXISTING,
        WinNT.FILE_FLAG_OPEN_REPARSE_POINT | WinNT.FILE_FLAG_BACKUP_SEMANTICS, null);
    if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
      throw new IOException("private Windows leaf cannot be opened");
    }
    try {
      return windowsLeafFacts(handle);
    } finally {
      Kernel32.INSTANCE.CloseHandle(handle);
    }
  }

  private static WindowsLeafFacts windowsLeafFacts(HANDLE handle) throws IOException {
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
  }

  private static boolean isWindows() {
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

  /** Package-private seam for deterministic channel-contract tests. */
  static FileChannel nativeFileChannelForTest(
      WindowsSharedFolderNativeBridge bridge,
      WindowsSharedFolderNativeBridge.NativeHandle handle) {
    return new NativeFileChannel(bridge, handle);
  }

  private static final class NativeFileChannel extends FileChannel {
    private final WindowsSharedFolderNativeBridge bridge;
    private WindowsSharedFolderNativeBridge.NativeHandle handle;
    private long position;

    private NativeFileChannel(
        WindowsSharedFolderNativeBridge bridge,
        WindowsSharedFolderNativeBridge.NativeHandle handle) {
      this.bridge = bridge;
      this.handle = handle;
    }

    @Override public synchronized int read(java.nio.ByteBuffer destination) throws IOException {
      if (!destination.hasRemaining()) return 0;
      byte[] bytes = new byte[Math.min(destination.remaining(), 64 * 1024)];
      bridge.seek(openHandle(), position);
      int read = bridge.read(openHandle(), bytes, 0, bytes.length);
      if (read > 0) {
        destination.put(bytes, 0, read);
        position += read;
      }
      return read;
    }

    @Override public synchronized int write(java.nio.ByteBuffer source) throws IOException {
      if (!source.hasRemaining()) return 0;
      byte[] bytes = new byte[Math.min(source.remaining(), 64 * 1024)];
      int originalPosition = source.position();
      source.get(bytes);
      bridge.seek(openHandle(), position);
      int written = bridge.write(openHandle(), bytes, 0, bytes.length);
      source.position(originalPosition + written);
      position += written;
      return written;
    }

    @Override public synchronized long read(
        java.nio.ByteBuffer[] destinations, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, destinations.length);
      long total = 0;
      for (int index = offset; index < offset + length; index++) {
        int read = read(destinations[index]);
        if (read < 0) return total == 0 ? -1 : total;
        total += read;
        if (destinations[index].hasRemaining()) break;
      }
      return total;
    }

    @Override public synchronized long write(
        java.nio.ByteBuffer[] sources, int offset, int length) throws IOException {
      java.util.Objects.checkFromIndexSize(offset, length, sources.length);
      long total = 0;
      for (int index = offset; index < offset + length; index++) {
        while (sources[index].hasRemaining()) {
          int written = write(sources[index]);
          if (written == 0) return total;
          total += written;
        }
      }
      return total;
    }

    @Override public synchronized long position() { return position; }
    @Override public synchronized FileChannel position(long newPosition) throws IOException {
      if (newPosition < 0) throw new IllegalArgumentException("negative position");
      bridge.seek(openHandle(), newPosition);
      position = newPosition;
      return this;
    }
    @Override public synchronized long size() throws IOException {
      return bridge.metadata(openHandle()).size();
    }
    @Override public synchronized FileChannel truncate(long size) throws IOException {
      if (size < 0) throw new IllegalArgumentException("negative size");
      bridge.truncate(openHandle(), size);
      if (position > size) position = size;
      return this;
    }
    @Override public synchronized void force(boolean metadata) throws IOException {
      bridge.flush(openHandle());
    }
    @Override public long transferTo(long position, long count, WritableByteChannel target)
        throws IOException {
      long transferred = 0;
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(
          (int) Math.min(Math.max(1, count), 64 * 1024));
      while (transferred < count) {
        buffer.clear().limit((int) Math.min(buffer.capacity(), count - transferred));
        int read = read(buffer, position + transferred);
        if (read <= 0) break;
        buffer.flip();
        long currentBufferTransferred = 0;
        while (buffer.hasRemaining()) {
          int written = target.write(buffer);
          if (written == 0) return transferred + currentBufferTransferred;
          currentBufferTransferred += written;
        }
        transferred += currentBufferTransferred;
      }
      return transferred;
    }
    @Override public long transferFrom(ReadableByteChannel source, long position, long count)
        throws IOException {
      long transferred = 0;
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(
          (int) Math.min(Math.max(1, count), 64 * 1024));
      while (transferred < count) {
        buffer.clear().limit((int) Math.min(buffer.capacity(), count - transferred));
        int read = source.read(buffer);
        if (read <= 0) break;
        buffer.flip();
        long writePosition = position + transferred;
        long currentBufferTransferred = 0;
        while (buffer.hasRemaining()) {
          int written = write(buffer, writePosition);
          if (written == 0) return transferred + currentBufferTransferred;
          writePosition += written;
          currentBufferTransferred += written;
        }
        transferred += currentBufferTransferred;
      }
      return transferred;
    }
    @Override public synchronized int read(java.nio.ByteBuffer destination, long at)
        throws IOException {
      long saved = position;
      try { position(at); return read(destination); }
      finally { position(saved); }
    }
    @Override public synchronized int write(java.nio.ByteBuffer source, long at)
        throws IOException {
      long saved = position;
      try { position(at); return write(source); }
      finally { position(saved); }
    }
    @Override public MappedByteBuffer map(MapMode mode, long position, long size) {
      throw new UnsupportedOperationException("native private mapping is unsupported");
    }
    @Override public FileLock lock(long position, long size, boolean shared) throws IOException {
      return nativeLock(position, size, shared);
    }
    @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException {
      return nativeLock(position, size, shared);
    }
    private FileLock nativeLock(long position, long size, boolean shared) throws IOException {
      openHandle();
      return new FileLock(this, position, size, shared) {
        private boolean valid = true;
        @Override public boolean isValid() { return valid && NativeFileChannel.this.isOpen(); }
        @Override public void release() { valid = false; }
      };
    }
    @Override protected void implCloseChannel() {
      if (handle != null) {
        var closing = handle;
        handle = null;
        bridge.close(closing);
      }
    }
    private WindowsSharedFolderNativeBridge.NativeHandle openHandle() throws IOException {
      if (handle == null) throw new IOException("native private file is closed");
      return handle;
    }
  }
}

package dev.christopherbell.sharedfolder.fs;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.DirectoryEntry;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeHandle;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.OpenKind;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.core.io.AbstractResource;
import org.springframework.stereotype.Component;

/**
 * Windows-only read boundary anchored to one held native directory handle.
 *
 * <p>When the feature is enabled on Windows, initialization opens the configured root once using
 * {@code OBJ_DONT_REPARSE}. Every later component is opened relative to that retained handle with
 * the same flag. There is intentionally no path-based NIO fallback in that mode. On non-Windows
 * systems the existing provider boundary remains available for local/test environments but does
 * not claim this Windows handle-relative guarantee.
 */
@Component
public final class WindowsSharedFolderReadBoundary {
  private static final int SAFE_OBJECT_ATTRIBUTES =
      WindowsSharedFolderNativeBridge.OBJ_CASE_INSENSITIVE
          | WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE;

  private final Path configuredRoot;
  private final WindowsSharedFolderNativeBridge bridge;
  private final boolean shouldActivate;
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
  private volatile NativeHandle rootHandle;

  /** Creates the production singleton. Native initialization is delayed until Spring starts it. */
  public WindowsSharedFolderReadBoundary(SharedFolderProperties properties) {
    this(properties.root(), new JnaWindowsSharedFolderNativeBridge(),
        properties.enabled() && isWindows());
  }

  private WindowsSharedFolderReadBoundary(
      Path configuredRoot, WindowsSharedFolderNativeBridge bridge, boolean shouldActivate) {
    this.configuredRoot = Objects.requireNonNull(configuredRoot, "shared-folder root is required");
    this.bridge = Objects.requireNonNull(bridge, "native bridge is required");
    this.shouldActivate = shouldActivate;
  }

  /** Returns a deliberately inactive instance for focused NIO unit tests and direct construction. */
  public static WindowsSharedFolderReadBoundary inactive() {
    return new WindowsSharedFolderReadBoundary(Path.of("."), new UnsupportedBridge(), false);
  }

  /** Creates an initialized native boundary with a deterministic bridge. */
  static WindowsSharedFolderReadBoundary forTest(
      Path configuredRoot, WindowsSharedFolderNativeBridge bridge) {
    WindowsSharedFolderReadBoundary boundary =
        new WindowsSharedFolderReadBoundary(configuredRoot, bridge, true);
    boundary.initialize();
    return boundary;
  }

  /** Opens and retains the root handle; enabled Windows deployments fail closed if this fails. */
  @PostConstruct
  public void initialize() {
    lifecycleLock.writeLock().lock();
    try {
      if (!shouldActivate || rootHandle != null) {
        return;
      }
      try {
        rootHandle = bridge.openRoot(configuredRoot, SAFE_OBJECT_ATTRIBUTES);
        NativeFileMetadata metadata = bridge.metadata(rootHandle);
        if (!metadata.directory()) {
          closeRootAfterFailedInitialization();
          throw unavailable();
        }
      } catch (NativeBoundaryException exception) {
        closeRootAfterFailedInitialization();
        throw unavailable(exception);
      }
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  /** Whether reads must use this native held-root boundary. */
  public boolean active() {
    return rootHandle != null;
  }

  /**
   * Whether this process is in Windows native-read mode, even during shutdown after the root closes.
   *
   * <p>Callers use this rather than {@link #active()} to avoid a path-based NIO fallback if the
   * held root has become unavailable.
   */
  public boolean nativeMode() {
    return shouldActivate;
  }

  /** Lists one directory from the retained root through opened directory handles only. */
  public List<DirectoryEntry> list(String decodedPath) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      List<String> segments = safeSegments(decodedPath, true);
      OpenedHandle directory = openSegments(segments, OpenKind.DIRECTORY);
      try {
        NativeFileMetadata metadata = bridge.metadata(directory.handle());
        if (!metadata.directory()) {
          throw unavailable();
        }
        List<DirectoryEntry> safe = new ArrayList<>();
        for (DirectoryEntry entry : bridge.listDirectory(directory.handle())) {
          if (entry == null || entry.reparsePoint() || (!entry.directory() && !entry.regularFile())) {
            continue;
          }
          try {
            SharedFolderPathResolver.validateSingleWindowsName(entry.name());
            safe.add(entry);
          } catch (UnsafeSharedPathException ignored) {
            // Native directory records are untrusted names just like request text.
          }
        }
        return List.copyOf(safe);
      } catch (NativeBoundaryException exception) {
        throw unavailable(exception);
      } finally {
        directory.closeIfOwned();
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  /** Captures metadata from a native file handle for a later stream open with a FileId recheck. */
  public NativeReadTarget file(String decodedPath) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      List<String> segments = safeSegments(decodedPath, false);
      OpenedHandle file = openSegments(segments, OpenKind.FILE);
      try {
        NativeFileMetadata metadata = bridge.metadata(file.handle());
        if (!metadata.regularFile()) {
          throw unavailable();
        }
        return new NativeReadTarget(List.copyOf(segments), metadata);
      } catch (NativeBoundaryException exception) {
        throw unavailable(exception);
      } finally {
        file.closeIfOwned();
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  private InputStream openVerifiedFile(NativeReadTarget target) throws IOException {
    lifecycleLock.readLock().lock();
    try {
      OpenedHandle file;
      try {
        file = openSegments(target.segments(), OpenKind.FILE);
      } catch (UnsafeSharedPathException exception) {
        throw notFound(exception);
      }
      try {
        NativeFileMetadata current = bridge.metadata(file.handle());
        if (!current.regularFile() || !target.metadata().identity().sameFile(current.identity())) {
          file.closeIfOwned();
          throw notFound(null);
        }
        return new NativeHandleInputStream(bridge, file.detach());
      } catch (NativeBoundaryException exception) {
        file.closeIfOwned();
        throw notFound(exception);
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  private OpenedHandle openSegments(List<String> segments, OpenKind finalKind) {
    NativeHandle current = rootHandle;
    boolean ownsCurrent = false;
    try {
      for (int index = 0; index < segments.size(); index++) {
        OpenKind kind = index + 1 == segments.size() ? finalKind : OpenKind.DIRECTORY;
        NativeHandle next = bridge.openRelative(current, segments.get(index), kind, SAFE_OBJECT_ATTRIBUTES);
        if (ownsCurrent) {
          closeQuietly(current);
        }
        current = next;
        ownsCurrent = true;
      }
      return new OpenedHandle(current, ownsCurrent);
    } catch (NativeBoundaryException exception) {
      if (ownsCurrent) {
        closeQuietly(current);
      }
      throw unavailable(exception);
    }
  }

  private List<String> safeSegments(String decodedPath, boolean allowEmpty) {
    try {
      return SharedFolderPathResolver.safeRelativeSegments(decodedPath, allowEmpty);
    } catch (UnsafeSharedPathException exception) {
      throw unavailable(exception);
    }
  }

  private void requireActive() {
    if (!active()) {
      throw unavailable();
    }
  }

  private void closeRootAfterFailedInitialization() {
    if (rootHandle != null) {
      closeQuietly(rootHandle);
      rootHandle = null;
    }
  }

  private void closeQuietly(NativeHandle handle) {
    try {
      bridge.close(handle);
    } catch (NativeBoundaryException ignored) {
      // No path is retried or exposed after close failure.
    }
  }

  private UnsafeSharedPathException unavailable() {
    return new UnsafeSharedPathException("Shared-folder item is not available");
  }

  private UnsafeSharedPathException unavailable(Throwable cause) {
    return new UnsafeSharedPathException("Shared-folder item is not available", cause);
  }

  private FileNotFoundException notFound(Throwable cause) {
    FileNotFoundException exception = new FileNotFoundException("Shared-folder item is no longer available");
    if (cause != null) {
      exception.initCause(cause);
    }
    return exception;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
  }

  /** A metadata probe whose resource verifies that the streamed handle retains the same FileId. */
  public final class NativeReadTarget {
    private final List<String> segments;
    private final NativeFileMetadata metadata;

    private NativeReadTarget(List<String> segments, NativeFileMetadata metadata) {
      this.segments = segments;
      this.metadata = metadata;
    }

    public NativeFileMetadata metadata() { return metadata; }

    List<String> segments() { return segments; }

    /** Returns a pathless resource that opens and verifies its native handle only when streamed. */
    public AbstractResource resource(String filename) {
      return new NativeReadResource(this, filename, metadata.size());
    }
  }

  private final class NativeReadResource extends AbstractResource {
    private final NativeReadTarget target;
    private final String filename;
    private final long size;

    private NativeReadResource(NativeReadTarget target, String filename, long size) {
      this.target = target;
      this.filename = Objects.requireNonNull(filename, "filename is required");
      this.size = size;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return openVerifiedFile(target);
    }

    @Override
    public long contentLength() { return size; }

    @Override
    public String getFilename() { return filename; }

    @Override
    public String getDescription() { return "shared-folder native read resource"; }
  }

  private final class OpenedHandle {
    private NativeHandle handle;
    private final boolean owned;

    private OpenedHandle(NativeHandle handle, boolean owned) {
      this.handle = handle;
      this.owned = owned;
    }

    NativeHandle handle() { return handle; }

    NativeHandle detach() {
      NativeHandle detached = handle;
      handle = null;
      return detached;
    }

    void closeIfOwned() {
      if (owned && handle != null) {
        closeQuietly(handle);
        handle = null;
      }
    }
  }

  private static final class NativeHandleInputStream extends InputStream {
    private final WindowsSharedFolderNativeBridge bridge;
    private NativeHandle handle;
    private long position;

    private NativeHandleInputStream(WindowsSharedFolderNativeBridge bridge, NativeHandle handle) {
      this.bridge = bridge;
      this.handle = handle;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int count = read(one, 0, 1);
      return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, buffer.length);
      if (length == 0) {
        return 0;
      }
      NativeHandle current = requireOpen();
      try {
        int count = bridge.read(current, buffer, offset, length);
        if (count > 0) {
          position += count;
        }
        return count;
      } catch (NativeBoundaryException exception) {
        throw new IOException("Shared-folder item is no longer available", exception);
      }
    }

    @Override
    public long skip(long count) throws IOException {
      if (count <= 0) {
        return 0;
      }
      NativeHandle current = requireOpen();
      try {
        long newPosition = bridge.seek(current, Math.addExact(position, count));
        long skipped = Math.max(0, newPosition - position);
        position = newPosition;
        return skipped;
      } catch (ArithmeticException | NativeBoundaryException exception) {
        throw new IOException("Shared-folder item is no longer available", exception);
      }
    }

    @Override
    public void close() throws IOException {
      NativeHandle current = handle;
      handle = null;
      if (current != null) {
        try {
          bridge.close(current);
        } catch (NativeBoundaryException exception) {
          throw new IOException("Shared-folder item is no longer available", exception);
        }
      }
    }

    private NativeHandle requireOpen() throws IOException {
      if (handle == null) {
        throw new IOException("Shared-folder stream is closed");
      }
      return handle;
    }
  }

  @PreDestroy
  public void destroy() {
    lifecycleLock.writeLock().lock();
    try {
      closeRootAfterFailedInitialization();
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  private static final class UnsupportedBridge implements WindowsSharedFolderNativeBridge {
    private NativeBoundaryException unavailable() {
      return new NativeBoundaryException("native shared-folder boundary is inactive", 0);
    }

    @Override public NativeHandle openRoot(Path path, int flags) { throw unavailable(); }
    @Override public NativeHandle openRelative(NativeHandle parent, String name, OpenKind kind, int flags) {
      throw unavailable();
    }
    @Override public NativeFileMetadata metadata(NativeHandle handle) { throw unavailable(); }
    @Override public List<DirectoryEntry> listDirectory(NativeHandle directory) { throw unavailable(); }
    @Override public int read(NativeHandle handle, byte[] buffer, int offset, int length) { throw unavailable(); }
    @Override public long seek(NativeHandle handle, long offset) { throw unavailable(); }
    @Override public void close(NativeHandle handle) { throw unavailable(); }
  }
}

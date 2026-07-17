package dev.christopherbell.sharedfolder.fs;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeHandle;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.OpenKind;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Native Windows write boundary anchored to retained visible and system-root directory handles.
 *
 * <p>All mutable visible and staging names are opened relative to those retained handles with
 * {@code OBJ_DONT_REPARSE}. This boundary intentionally exposes no mutable absolute pathname.
 */
@Component
public final class WindowsSharedFolderMutationBoundary {
  private static final int SAFE_OBJECT_ATTRIBUTES = WindowsSharedFolderNativeBridge.OBJ_CASE_INSENSITIVE
      | WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE;
  private static final String STAGING_DIRECTORY = "shared-folder-upload-staging";
  private static final String MUTATION_QUARANTINE_DIRECTORY = "shared-folder-mutation-quarantine";

  private final Path configuredRoot;
  private final Path configuredSystemRoot;
  private final WindowsSharedFolderNativeBridge bridge;
  private final boolean shouldActivate;
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
  private final ReentrantLock mutationLock = new ReentrantLock(true);
  private volatile NativeHandle rootHandle;
  private volatile NativeHandle systemRootHandle;
  private volatile NativeHandle stagingRootHandle;
  private volatile NativeHandle mutationQuarantineRootHandle;

  public WindowsSharedFolderMutationBoundary(SharedFolderProperties properties) {
    this(properties.root(), properties.systemRoot(), new JnaWindowsSharedFolderNativeBridge(),
        properties.enabled() && isWindows());
  }

  private WindowsSharedFolderMutationBoundary(
      Path configuredRoot, Path configuredSystemRoot, WindowsSharedFolderNativeBridge bridge,
      boolean shouldActivate) {
    this.configuredRoot = Objects.requireNonNull(configuredRoot, "shared-folder root is required");
    this.configuredSystemRoot = Objects.requireNonNull(configuredSystemRoot, "system root is required");
    this.bridge = Objects.requireNonNull(bridge, "native bridge is required");
    this.shouldActivate = shouldActivate;
  }

  public static WindowsSharedFolderMutationBoundary inactive() {
    return new WindowsSharedFolderMutationBoundary(Path.of("."), Path.of("."),
        new UnsupportedBridge(), false);
  }

  static WindowsSharedFolderMutationBoundary forTest(
      Path root, Path systemRoot, WindowsSharedFolderNativeBridge bridge) {
    WindowsSharedFolderMutationBoundary result =
        new WindowsSharedFolderMutationBoundary(root, systemRoot, bridge, true);
    result.initialize();
    return result;
  }

  @PostConstruct
  public void initialize() {
    lifecycleLock.writeLock().lock();
    try {
      if (!shouldActivate || rootHandle != null) {
        return;
      }
      rootHandle = bridge.openRootForMutation(configuredRoot, SAFE_OBJECT_ATTRIBUTES);
      systemRootHandle = bridge.openRootForMutation(configuredSystemRoot, SAFE_OBJECT_ATTRIBUTES);
      if (!bridge.metadata(rootHandle).directory() || !bridge.metadata(systemRootHandle).directory()) {
        closeAll();
        throw unavailable();
      }
    } catch (NativeBoundaryException exception) {
      closeAll();
      throw unavailable(exception);
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  /** Whether Windows writes must use this boundary and may never fall back to path-based NIO. */
  public boolean nativeMode() { return shouldActivate; }

  public boolean active() { return rootHandle != null && systemRootHandle != null; }

  public NativeFileMetadata metadata(String relativePath) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      OpenedHandle handle = openVisible(relativePath, OpenKind.ANY, false, false, false);
      try {
        return bridge.metadata(handle.handle());
      } finally {
        handle.close();
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  /** Verifies one visible destination directory, including the retained visible root itself. */
  public NativeFileMetadata directory(String relativePath) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      OpenedHandle handle = openVisible(relativePath, OpenKind.DIRECTORY, true, false, false);
      try {
        NativeFileMetadata metadata = bridge.metadata(handle.handle());
        if (!metadata.directory()) {
          throw unavailable();
        }
        return metadata;
      } finally {
        handle.close();
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  /** Queries reserve capacity from the retained system-root volume capability, never a pathname. */
  public long usableSystemBytes() {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      return bridge.usableSpace(systemRootHandle);
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  public NativeFileMetadata createDirectory(String parentPath, String name) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      SharedFolderPathResolver.validateSingleWindowsName(name);
      OpenedHandle parent = openVisible(parentPath, OpenKind.DIRECTORY, true, true, false);
      try {
        NativeHandle created = bridge.createRelative(parent.handle(), name, OpenKind.DIRECTORY,
            SAFE_OBJECT_ATTRIBUTES);
        try {
          return bridge.metadata(created);
        } finally {
          closeQuietly(created);
        }
      } finally {
        parent.close();
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  public NativeFileMetadata rename(
      String sourcePath, String destinationParentPath, String name, boolean replace) {
    return rename(sourcePath, destinationParentPath, name, replace, null);
  }

  /** Renames only when the final held source handle still matches the earlier observation. */
  public NativeFileMetadata rename(
      String sourcePath, String destinationParentPath, String name, boolean replace,
      NativeFileMetadata expectedSource) {
    return rename(sourcePath, destinationParentPath, name, replace, expectedSource, null);
  }

  /** Serializes the final source and optional replacement-target recheck with the native rename. */
  public NativeFileMetadata rename(
      String sourcePath, String destinationParentPath, String name, boolean replace,
      NativeFileMetadata expectedSource, NativeFileMetadata expectedTarget) {
    mutationLock.lock();
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      SharedFolderPathResolver.validateSingleWindowsName(name);
      OpenedHandle source = openVisible(sourcePath, OpenKind.ANY, false, true, true);
      OpenedHandle destination = openVisible(
          destinationParentPath, OpenKind.DIRECTORY, true, true, false);
      OpenedHandle target = expectedTarget == null ? null
          : openVisible(relative(destinationParentPath, name), OpenKind.ANY, false, true, true);
      try {
        requireSameObservation(expectedSource, bridge.metadata(source.handle()));
        if (target != null) {
          requireSameObservation(expectedTarget, bridge.metadata(target.handle()));
        }
        if (replace) {
          requireReplacementTarget(target);
          replacePinned(source.handle(), destination.handle(), name, target);
        } else {
          bridge.rename(source.handle(), destination.handle(), name, false);
        }
      } finally {
        if (target != null) {
          target.close();
        }
        destination.close();
        source.close();
      }
      return metadata(relative(destinationParentPath, name));
    } finally {
      lifecycleLock.readLock().unlock();
      mutationLock.unlock();
    }
  }

  public void delete(String sourcePath) {
    delete(sourcePath, null);
  }

  /** Deletes only when the final held source handle still matches the earlier observation. */
  public void delete(String sourcePath, NativeFileMetadata expectedSource) {
    mutationLock.lock();
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      OpenedHandle source = openVisible(sourcePath, OpenKind.ANY, false, true, true);
      try {
        requireSameObservation(expectedSource, bridge.metadata(source.handle()));
        bridge.delete(source.handle());
      } finally {
        source.close();
      }
    } finally {
      lifecycleLock.readLock().unlock();
      mutationLock.unlock();
    }
  }

  /** Creates a private random staging file beneath the retained system-root handle. */
  public NativeStagingFile createStaging(String key) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(key);
      NativeHandle created = bridge.createRelative(stagingRoot(), key, OpenKind.FILE,
          SAFE_OBJECT_ATTRIBUTES);
      return new NativeStagingFile(created);
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  /** Opens a private random staging file through the retained system-root handle. */
  public NativeStagingFile staging(String key) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(key);
      return new NativeStagingFile(bridge.openRelativeForMutation(
          stagingRoot(), key, OpenKind.FILE, SAFE_OBJECT_ATTRIBUTES));
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  /** Atomically moves a staged file onto a visible target after checking the native volume serial. */
  public NativeFileMetadata finalizeStaging(
      String key, String destinationParentPath, String name, boolean replace) {
    return finalizeStaging(key, destinationParentPath, name, replace, null);
  }

  /** Serializes same-volume finalization with an optional observed replacement target recheck. */
  public NativeFileMetadata finalizeStaging(
      String key, String destinationParentPath, String name, boolean replace,
      NativeFileMetadata expectedTarget) {
    mutationLock.lock();
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(key);
      SharedFolderPathResolver.validateSingleWindowsName(name);
      NativeHandle source = bridge.openRelativeForMutation(
          stagingRoot(), key, OpenKind.FILE, SAFE_OBJECT_ATTRIBUTES);
      OpenedHandle destination = openVisible(
          destinationParentPath, OpenKind.DIRECTORY, true, true, false);
      OpenedHandle target = expectedTarget == null ? null
          : openVisible(relative(destinationParentPath, name), OpenKind.ANY, false, true, true);
      try {
        if (bridge.metadata(source).identity().volumeSerial()
            != bridge.metadata(destination.handle()).identity().volumeSerial()) {
          throw new NativeBoundaryException("staging and visible roots are on different volumes", 0);
        }
        if (target != null) {
          requireSameObservation(expectedTarget, bridge.metadata(target.handle()));
        }
        if (replace) {
          requireReplacementTarget(target);
          replacePinned(source, destination.handle(), name, target);
        } else {
          bridge.rename(source, destination.handle(), name, false);
        }
      } finally {
        if (target != null) {
          target.close();
        }
        destination.close();
        closeQuietly(source);
      }
      return metadata(relative(destinationParentPath, name));
    } finally {
      lifecycleLock.readLock().unlock();
      mutationLock.unlock();
    }
  }

  public void deleteStaging(String key) {
    try (NativeStagingFile file = staging(key)) {
      bridge.delete(file.handle);
    }
  }

  /** Deletes staging or confirms that NTFS already reports the private key absent. */
  public boolean deleteStagingIfExists(String key) {
    try {
      deleteStaging(key);
      return true;
    } catch (NativeBoundaryException exception) {
      if (exception.ntStatus() == 0xC0000034 || exception.ntStatus() == 0xC000003A) {
        return true;
      }
      throw exception;
    }
  }

  /** Moves the exact observed visible target into a caller-chosen private journal key. */
  public NativeFileMetadata quarantineVisible(
      String visiblePath, String quarantineKey, NativeFileMetadata expectedTarget) {
    mutationLock.lock();
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(quarantineKey);
      OpenedHandle target = openVisible(visiblePath, OpenKind.ANY, false, true, true);
      try {
        requireSameObservation(expectedTarget, bridge.metadata(target.handle()));
        requireReplacementTarget(target);
        if (bridge.metadata(target.handle()).identity().volumeSerial()
            != bridge.metadata(mutationQuarantineRoot()).identity().volumeSerial()) {
          throw new NativeBoundaryException("replacement roots are on different volumes", 0);
        }
        bridge.rename(target.handle(), mutationQuarantineRoot(), quarantineKey, false);
        return bridge.metadata(target.handle());
      } finally {
        target.close();
      }
    } finally {
      lifecycleLock.readLock().unlock();
      mutationLock.unlock();
    }
  }

  /** Moves the exact observed visible source to a visible name without replacement. */
  public NativeFileMetadata moveVisibleNoReplace(
      String sourcePath, String destinationParentPath, String name,
      NativeFileMetadata expectedSource) {
    return rename(sourcePath, destinationParentPath, name, false, expectedSource, null);
  }

  /** Returns metadata for a private mutation-quarantine entry. */
  public NativeFileMetadata quarantineMetadata(String quarantineKey) {
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(quarantineKey);
      NativeHandle handle = bridge.openRelativeForMutation(
          mutationQuarantineRoot(), quarantineKey, OpenKind.ANY, SAFE_OBJECT_ATTRIBUTES);
      try {
        return bridge.metadata(handle);
      } finally {
        closeQuietly(handle);
      }
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  /** Restores the exact quarantined item only when the visible name is still free. */
  public NativeFileMetadata restoreQuarantine(
      String quarantineKey, String destinationParentPath, String name,
      NativeFileMetadata expectedTarget) {
    mutationLock.lock();
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(quarantineKey);
      SharedFolderPathResolver.validateSingleWindowsName(name);
      NativeHandle source = bridge.openRelativeForMutation(
          mutationQuarantineRoot(), quarantineKey, OpenKind.ANY, SAFE_OBJECT_ATTRIBUTES);
      OpenedHandle destination = openVisible(
          destinationParentPath, OpenKind.DIRECTORY, true, true, false);
      try {
        requireSameObservation(expectedTarget, bridge.metadata(source));
        bridge.rename(source, destination.handle(), name, false);
      } finally {
        destination.close();
        closeQuietly(source);
      }
      return metadata(relative(destinationParentPath, name));
    } finally {
      lifecycleLock.readLock().unlock();
      mutationLock.unlock();
    }
  }

  /** Deletes the exact quarantined item after a committed replacement. */
  public void deleteQuarantine(String quarantineKey, NativeFileMetadata expectedTarget) {
    mutationLock.lock();
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(quarantineKey);
      NativeHandle target = bridge.openRelativeForMutation(
          mutationQuarantineRoot(), quarantineKey, OpenKind.ANY, SAFE_OBJECT_ATTRIBUTES);
      try {
        requireSameObservation(expectedTarget, bridge.metadata(target));
        bridge.delete(target);
      } finally {
        closeQuietly(target);
      }
    } finally {
      lifecycleLock.readLock().unlock();
      mutationLock.unlock();
    }
  }

  /** Restores a just-finalized visible file to its private staging key after metadata save fails. */
  public void restoreFinalized(
      String visiblePath, String stagingKey, NativeFileMetadata expectedVisible) {
    mutationLock.lock();
    lifecycleLock.readLock().lock();
    try {
      requireActive();
      validateStagingKey(stagingKey);
      OpenedHandle source = openVisible(visiblePath, OpenKind.FILE, false, true, true);
      try {
        requireSameObservation(expectedVisible, bridge.metadata(source.handle()));
        bridge.rename(source.handle(), stagingRoot(), stagingKey, false);
      } finally {
        source.close();
      }
    } finally {
      lifecycleLock.readLock().unlock();
      mutationLock.unlock();
    }
  }

  private NativeHandle stagingRoot() {
    NativeHandle current = stagingRootHandle;
    if (current != null) {
      return current;
    }
    lifecycleLock.readLock().unlock();
    lifecycleLock.writeLock().lock();
    try {
      requireActive();
      if (stagingRootHandle == null) {
        try {
          stagingRootHandle = bridge.openRelativeForMutation(systemRootHandle, STAGING_DIRECTORY,
              OpenKind.DIRECTORY, SAFE_OBJECT_ATTRIBUTES);
        } catch (NativeBoundaryException missing) {
          stagingRootHandle = bridge.createRelative(systemRootHandle, STAGING_DIRECTORY,
              OpenKind.DIRECTORY, SAFE_OBJECT_ATTRIBUTES);
        }
        if (!bridge.metadata(stagingRootHandle).directory()) {
          closeQuietly(stagingRootHandle);
          stagingRootHandle = null;
          throw unavailable();
        }
      }
      return stagingRootHandle;
    } finally {
      lifecycleLock.readLock().lock();
      lifecycleLock.writeLock().unlock();
    }
  }

  private NativeHandle mutationQuarantineRoot() {
    NativeHandle current = mutationQuarantineRootHandle;
    if (current != null) {
      return current;
    }
    lifecycleLock.readLock().unlock();
    lifecycleLock.writeLock().lock();
    try {
      requireActive();
      if (mutationQuarantineRootHandle == null) {
        try {
          mutationQuarantineRootHandle = bridge.openRelativeForMutation(
              systemRootHandle, MUTATION_QUARANTINE_DIRECTORY,
              OpenKind.DIRECTORY, SAFE_OBJECT_ATTRIBUTES);
        } catch (NativeBoundaryException missing) {
          mutationQuarantineRootHandle = bridge.createRelative(
              systemRootHandle, MUTATION_QUARANTINE_DIRECTORY,
              OpenKind.DIRECTORY, SAFE_OBJECT_ATTRIBUTES);
        }
        if (!bridge.metadata(mutationQuarantineRootHandle).directory()) {
          closeQuietly(mutationQuarantineRootHandle);
          mutationQuarantineRootHandle = null;
          throw unavailable();
        }
      }
      return mutationQuarantineRootHandle;
    } finally {
      lifecycleLock.readLock().lock();
      lifecycleLock.writeLock().unlock();
    }
  }

  private void requireReplacementTarget(OpenedHandle target) {
    if (target == null) {
      throw new NativeBoundaryException("observed replacement target is required", 0);
    }
    NativeFileMetadata metadata = bridge.metadata(target.handle());
    if (metadata.directory() && !bridge.listDirectory(target.handle()).isEmpty()) {
      throw new NativeBoundaryException("non-empty replacement directory conflicts", 0);
    }
  }

  private void replacePinned(
      NativeHandle source, NativeHandle destination, String name, OpenedHandle target) {
    NativeHandle quarantineRoot = mutationQuarantineRoot();
    long volume = bridge.metadata(destination).identity().volumeSerial();
    if (bridge.metadata(source).identity().volumeSerial() != volume
        || bridge.metadata(target.handle()).identity().volumeSerial() != volume
        || bridge.metadata(quarantineRoot).identity().volumeSerial() != volume) {
      throw new NativeBoundaryException("replacement roots are on different volumes", 0);
    }
    String quarantineKey = java.util.UUID.randomUUID().toString();
    bridge.rename(target.handle(), quarantineRoot, quarantineKey, false);
    try {
      bridge.rename(source, destination, name, false);
    } catch (NativeBoundaryException failure) {
      try {
        bridge.rename(target.handle(), destination, name, false);
      } catch (NativeBoundaryException restoreFailure) {
        failure.addSuppressed(restoreFailure);
      }
      throw failure;
    }
    bridge.delete(target.handle());
  }

  private OpenedHandle openVisible(
      String relativePath, OpenKind finalKind, boolean allowEmpty,
      boolean pinChain, boolean finalMutation) {
    List<String> segments = safeSegments(relativePath, allowEmpty);
    NativeHandle current = rootHandle;
    List<NativeHandle> owned = new ArrayList<>();
    try {
      for (int index = 0; index < segments.size(); index++) {
        OpenKind kind = index + 1 == segments.size() ? finalKind : OpenKind.DIRECTORY;
        boolean leaf = index + 1 == segments.size();
        NativeHandle next = finalMutation && leaf
            ? bridge.openRelativeForMutation(current, segments.get(index), kind, SAFE_OBJECT_ATTRIBUTES)
            : pinChain
                ? bridge.openRelativePinned(current, segments.get(index), kind, SAFE_OBJECT_ATTRIBUTES)
                : bridge.openRelative(current, segments.get(index), kind, SAFE_OBJECT_ATTRIBUTES);
        current = next;
        owned.add(next);
      }
      return new OpenedHandle(current, owned);
    } catch (NativeBoundaryException exception) {
      for (int index = owned.size() - 1; index >= 0; index--) {
        closeQuietly(owned.get(index));
      }
      throw exception;
    }
  }

  private List<String> safeSegments(String value, boolean allowEmpty) {
    try {
      return SharedFolderPathResolver.safeRelativeSegments(value, allowEmpty);
    } catch (UnsafeSharedPathException exception) {
      throw unavailable(exception);
    }
  }

  private String relative(String parent, String name) {
    return parent == null || parent.isEmpty() ? name : parent + "/" + name;
  }

  private void validateStagingKey(String key) {
    if (key == null || !key.matches("[0-9a-fA-F-]{36}")) {
      throw unavailable();
    }
  }

  private void requireActive() {
    if (!active()) {
      throw unavailable();
    }
  }

  private void requireSameObservation(
      NativeFileMetadata expected, NativeFileMetadata current) {
    if (expected == null) {
      return;
    }
    if (!expected.identity().sameFile(current.identity())
        || expected.directory() != current.directory()
        || expected.regularFile() != current.regularFile()
        || expected.size() != current.size()
        || !expected.modifiedAt().equals(current.modifiedAt())) {
      throw new NativeBoundaryException("native shared-folder item changed", 0);
    }
  }

  private void closeQuietly(NativeHandle handle) {
    try {
      bridge.close(handle);
    } catch (NativeBoundaryException ignored) {
      // Never retry a failed native close through a pathname.
    }
  }

  private void closeAll() {
    if (mutationQuarantineRootHandle != null) {
      closeQuietly(mutationQuarantineRootHandle);
      mutationQuarantineRootHandle = null;
    }
    if (stagingRootHandle != null) {
      closeQuietly(stagingRootHandle);
      stagingRootHandle = null;
    }
    if (systemRootHandle != null) {
      closeQuietly(systemRootHandle);
      systemRootHandle = null;
    }
    if (rootHandle != null) {
      closeQuietly(rootHandle);
      rootHandle = null;
    }
  }

  private UnsafeSharedPathException unavailable() {
    return new UnsafeSharedPathException("Shared-folder item is not available");
  }

  private UnsafeSharedPathException unavailable(Throwable cause) {
    return new UnsafeSharedPathException("Shared-folder item is not available", cause);
  }

  @PreDestroy
  public void destroy() {
    lifecycleLock.writeLock().lock();
    try {
      closeAll();
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  /** An opened private staging file; callers can only stream bytes through its held handle. */
  public final class NativeStagingFile implements AutoCloseable {
    private NativeHandle handle;

    private NativeStagingFile(NativeHandle handle) { this.handle = handle; }

    public NativeFileMetadata metadata() { return bridge.metadata(requireOpen()); }
    public long seek(long offset) { return bridge.seek(requireOpen(), offset); }
    public int read(byte[] buffer, int offset, int length) {
      return bridge.read(requireOpen(), buffer, offset, length);
    }
    public int write(byte[] buffer, int offset, int length) {
      return bridge.write(requireOpen(), buffer, offset, length);
    }
    public void flush() { bridge.flush(requireOpen()); }
    public void truncate(long size) { bridge.truncate(requireOpen(), size); }

    @Override
    public void close() {
      if (handle != null) {
        NativeHandle closing = handle;
        handle = null;
        closeQuietly(closing);
      }
    }

    private NativeHandle requireOpen() {
      if (handle == null) {
        throw new NativeBoundaryException("native staging handle is closed", 0);
      }
      return handle;
    }
  }

  private final class OpenedHandle {
    private NativeHandle handle;
    private final List<NativeHandle> owned;

    private OpenedHandle(NativeHandle handle, List<NativeHandle> owned) {
      this.handle = handle;
      this.owned = owned;
    }

    NativeHandle handle() { return handle; }

    void close() {
      if (handle != null) {
        handle = null;
        for (int index = owned.size() - 1; index >= 0; index--) {
          closeQuietly(owned.get(index));
        }
        owned.clear();
      }
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
  }

  private static final class UnsupportedBridge implements WindowsSharedFolderNativeBridge {
    private NativeBoundaryException unavailable() {
      return new NativeBoundaryException("native shared-folder boundary is inactive", 0);
    }
    @Override public NativeHandle openRoot(Path path, int flags) { throw unavailable(); }
    @Override public NativeHandle openRootForMutation(Path path, int flags) { throw unavailable(); }
    @Override public NativeHandle openRelative(NativeHandle parent, String name, OpenKind kind, int flags) {
      throw unavailable();
    }
    @Override public NativeHandle openRelativePinned(
        NativeHandle parent, String name, OpenKind kind, int flags) { throw unavailable(); }
    @Override public NativeFileMetadata metadata(NativeHandle handle) { throw unavailable(); }
    @Override public List<DirectoryEntry> listDirectory(NativeHandle directory) { throw unavailable(); }
    @Override public int read(NativeHandle handle, byte[] buffer, int offset, int length) { throw unavailable(); }
    @Override public long seek(NativeHandle handle, long offset) { throw unavailable(); }
    @Override public void close(NativeHandle handle) { throw unavailable(); }
  }
}

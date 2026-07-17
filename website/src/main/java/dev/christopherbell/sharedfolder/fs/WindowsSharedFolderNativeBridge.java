package dev.christopherbell.sharedfolder.fs;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Native Windows handle operations used by the shared-folder trusted-root boundary.
 *
 * <p>This narrow bridge deliberately accepts only a retained parent handle plus one grammar-checked
 * name. Production code must never turn a validated relative path back into an absolute NIO path
 * while the Windows boundary is active.
 */
public interface WindowsSharedFolderNativeBridge {
  int OBJ_CASE_INSENSITIVE = 0x0040;
  int OBJ_DONT_REPARSE = 0x1000;
  int STATUS_REPARSE_POINT_ENCOUNTERED = 0xC000050B;

  /** Opens the configured root by its absolute native name and returns a retained directory handle. */
  NativeHandle openRoot(Path rootPath, int objectAttributes);

  /** Opens a retained mutation root while denying external delete/rename sharing. */
  default NativeHandle openRootForMutation(Path rootPath, int objectAttributes) {
    throw unsupportedMutation();
  }

  /** Opens one validated child relative to an already trusted directory handle. */
  NativeHandle openRelative(NativeHandle parent, String name, OpenKind kind, int objectAttributes);

  /** Pins one existing descendant against external delete/rename without requesting DELETE access. */
  default NativeHandle openRelativePinned(
      NativeHandle parent, String name, OpenKind kind, int objectAttributes) {
    throw unsupportedMutation();
  }

  /** Opens one existing child with DELETE access for a later handle-relative rename or delete. */
  default NativeHandle openRelativeForMutation(
      NativeHandle parent, String name, OpenKind kind, int objectAttributes) {
    throw unsupportedMutation();
  }

  /** Creates one new child relative to a retained trusted directory handle without overwrite. */
  default NativeHandle createRelative(
      NativeHandle parent, String name, OpenKind kind, int objectAttributes) {
    throw unsupportedMutation();
  }

  /** Reads metadata from the opened handle rather than a mutable pathname. */
  NativeFileMetadata metadata(NativeHandle handle);

  /** Enumerates the supplied opened directory handle without constructing child paths. */
  List<DirectoryEntry> listDirectory(NativeHandle directory);

  /** Reads from an opened ordinary-file handle. Returns {@code -1} at EOF. */
  int read(NativeHandle handle, byte[] buffer, int offset, int length);

  /** Moves an opened ordinary-file handle to an absolute byte position. */
  long seek(NativeHandle handle, long offset);

  /** Appends or writes bytes at the current position of an already opened native file handle. */
  default int write(NativeHandle handle, byte[] buffer, int offset, int length) {
    throw unsupportedMutation();
  }

  /** Durably flushes a previously written native file handle before its state is recorded. */
  default void flush(NativeHandle handle) {
    throw unsupportedMutation();
  }

  /** Sets the end of file for rollback of an uncommitted staged append. */
  default void truncate(NativeHandle handle, long size) {
    throw unsupportedMutation();
  }

  /** Renames one opened source into an opened parent directory using native handle-relative APIs. */
  default void rename(
      NativeHandle source, NativeHandle destinationParent, String name, boolean replace) {
    throw unsupportedMutation();
  }

  /** Marks an opened file or directory for physical deletion through the native handle. */
  default void delete(NativeHandle source) {
    throw unsupportedMutation();
  }

  /** Returns usable bytes for the volume containing one retained directory handle. */
  default long usableSpace(NativeHandle volumeHandle) {
    throw unsupportedMutation();
  }

  /** Closes one native handle. Implementations must make no absolute path available to callers. */
  void close(NativeHandle handle);

  private NativeBoundaryException unsupportedMutation() {
    return new NativeBoundaryException("native shared-folder mutation is unavailable", 0);
  }

  /** Opaque native handle wrapper. */
  record NativeHandle(Object value) {
    public NativeHandle {
      if (value == null) {
        throw new IllegalArgumentException("native handle is required");
      }
    }
  }

  /** Requested opened object kind. */
  enum OpenKind { DIRECTORY, FILE, ANY }

  /** Stable identity reported by {@code FileIdInfo}: volume serial and 128-bit file id. */
  final class NativeFileIdentity {
    private final long volumeSerial;
    private final byte[] fileId;

    public NativeFileIdentity(long volumeSerial, byte[] fileId) {
      if (fileId == null || fileId.length != 16) {
        throw new IllegalArgumentException("native file id must contain 128 bits");
      }
      this.volumeSerial = volumeSerial;
      this.fileId = fileId.clone();
    }

    public long volumeSerial() { return volumeSerial; }

    public byte[] fileId() { return fileId.clone(); }

    /** Returns whether this is the same Windows volume-plus-file identity. */
    public boolean sameFile(NativeFileIdentity other) {
      return other != null && volumeSerial == other.volumeSerial && Arrays.equals(fileId, other.fileId);
    }
  }

  /** Metadata obtained from an opened handle. */
  record NativeFileMetadata(
      NativeFileIdentity identity,
      boolean directory,
      boolean regularFile,
      long size,
      Instant modifiedAt) {
    public NativeFileMetadata {
      if (identity == null || size < 0 || modifiedAt == null) {
        throw new IllegalArgumentException("native metadata is incomplete");
      }
    }
  }

  /** One child returned by an opened-directory enumeration. */
  record DirectoryEntry(
      String name,
      NativeFileIdentity identity,
      boolean directory,
      boolean regularFile,
      boolean reparsePoint,
      long size,
      Instant modifiedAt) {}

  /** Native failure with an optional raw NTSTATUS for fail-closed error mapping. */
  final class NativeBoundaryException extends RuntimeException {
    private final int ntStatus;

    public NativeBoundaryException(String message, int ntStatus) {
      super(message);
      this.ntStatus = ntStatus;
    }

    public NativeBoundaryException(String message, int ntStatus, Throwable cause) {
      super(message, cause);
      this.ntStatus = ntStatus;
    }

    public int ntStatus() { return ntStatus; }
  }
}

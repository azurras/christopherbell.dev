package dev.christopherbell.sharedfolder.fs;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** JNA implementation of the held-root Windows native bridge. */
final class JnaWindowsSharedFolderNativeBridge implements WindowsSharedFolderNativeBridge {
  private static final int FILE_LIST_DIRECTORY = 0x0001;
  private static final int FILE_READ_DATA = 0x0001;
  private static final int FILE_TRAVERSE = 0x0020;
  private static final int FILE_READ_ATTRIBUTES = 0x0080;
  private static final int SYNCHRONIZE = 0x00100000;
  private static final int FILE_SHARE_READ_WRITE_DELETE = 0x0007;
  private static final int FILE_OPEN = 1;
  private static final int FILE_DIRECTORY_FILE = 0x0001;
  private static final int FILE_SYNCHRONOUS_IO_NONALERT = 0x0020;
  private static final int FILE_NON_DIRECTORY_FILE = 0x0040;
  private static final int FILE_ATTRIBUTE_NORMAL = 0x0080;
  private static final int FILE_ATTRIBUTE_DIRECTORY = 0x0010;
  private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x0400;
  private static final int ERROR_NO_MORE_FILES = 18;
  private static final int ERROR_HANDLE_EOF = 38;
  private static final int FILE_BEGIN = 0;
  private static final int FILE_ID_BOTH_DIRECTORY_INFO_BUFFER = 64 * 1024;
  private static final int FILE_ID_BOTH_DIRECTORY_INFO_FILE_ATTRIBUTES = 56;
  private static final int FILE_ID_BOTH_DIRECTORY_INFO_FILE_NAME_LENGTH = 60;
  private static final int FILE_ID_BOTH_DIRECTORY_INFO_LAST_WRITE_TIME = 24;
  private static final int FILE_ID_BOTH_DIRECTORY_INFO_END_OF_FILE = 40;
  private static final int FILE_ID_BOTH_DIRECTORY_INFO_FILE_ID = 96;
  private static final int FILE_ID_BOTH_DIRECTORY_INFO_FILE_NAME = 104;
  private static final long FILETIME_UNIX_EPOCH = 116444736000000000L;
  private static final long HUNDRED_NANOSECONDS_PER_SECOND = 10_000_000L;

  private volatile NtDll ntDll;
  private volatile Kernel32Ext kernel32Ext;

  @Override
  public NativeHandle openRoot(Path rootPath, int objectAttributes) {
    String absolute = rootPath.toAbsolutePath().normalize().toString();
    return open(ntPath(absolute), null, OpenKind.DIRECTORY, objectAttributes);
  }

  @Override
  public NativeHandle openRelative(
      NativeHandle parent, String name, OpenKind kind, int objectAttributes) {
    return open(name, nativeHandle(parent), kind, objectAttributes);
  }

  @Override
  public NativeFileMetadata metadata(NativeHandle nativeHandle) {
    HANDLE handle = nativeHandle(nativeHandle);
    WinBase.FILE_ID_INFO id = new WinBase.FILE_ID_INFO();
    if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
        handle, WinBase.FileIdInfo, id.getPointer(), new DWORD(WinBase.FILE_ID_INFO.sizeOf()))) {
      throw win32Failure("native file identity query failed");
    }
    id.read();
    WinBase.FILE_BASIC_INFO basic = new WinBase.FILE_BASIC_INFO();
    if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
        handle, WinBase.FileBasicInfo, basic.getPointer(), new DWORD(WinBase.FILE_BASIC_INFO.sizeOf()))) {
      throw win32Failure("native file metadata query failed");
    }
    basic.read();
    WinBase.FILE_STANDARD_INFO standard = new WinBase.FILE_STANDARD_INFO();
    if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
        handle, WinBase.FileStandardInfo, standard.getPointer(),
        new DWORD(WinBase.FILE_STANDARD_INFO.sizeOf()))) {
      throw win32Failure("native file size query failed");
    }
    standard.read();
    int attributes = basic.FileAttributes;
    boolean directory = (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0 || standard.Directory;
    boolean reparse = (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    return new NativeFileMetadata(
        new NativeFileIdentity(id.VolumeSerialNumber, fileIdBytes(id)),
        directory,
        !directory && !reparse,
        Math.max(0, standard.EndOfFile.getValue()),
        toInstant(basic.LastWriteTime.getValue()));
  }

  @Override
  public List<DirectoryEntry> listDirectory(NativeHandle nativeHandle) {
    HANDLE handle = nativeHandle(nativeHandle);
    List<DirectoryEntry> entries = new ArrayList<>();
    boolean restart = true;
    while (true) {
      Memory buffer = new Memory(FILE_ID_BOTH_DIRECTORY_INFO_BUFFER);
      int informationClass = restart ? WinBase.FileIdBothDirectoryRestartInfo
          : WinBase.FileIdBothDirectoryInfo;
      if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(
          handle, informationClass, buffer, new DWORD(FILE_ID_BOTH_DIRECTORY_INFO_BUFFER))) {
        int error = Kernel32.INSTANCE.GetLastError();
        if (error == ERROR_NO_MORE_FILES) {
          return List.copyOf(entries);
        }
        throw new NativeBoundaryException("native directory enumeration failed", ntStatusForWin32(error));
      }
      restart = false;
      long offset = 0;
      while (true) {
        int next = buffer.getInt(offset);
        int attributes = buffer.getInt(offset + FILE_ID_BOTH_DIRECTORY_INFO_FILE_ATTRIBUTES);
        int nameLength = buffer.getInt(offset + FILE_ID_BOTH_DIRECTORY_INFO_FILE_NAME_LENGTH);
        if (nameLength < 0 || offset + FILE_ID_BOTH_DIRECTORY_INFO_FILE_NAME + nameLength
            > FILE_ID_BOTH_DIRECTORY_INFO_BUFFER) {
          throw new NativeBoundaryException("native directory record is invalid", 0);
        }
        String name = new String(buffer.getCharArray(
            offset + FILE_ID_BOTH_DIRECTORY_INFO_FILE_NAME, nameLength / 2));
        long fileId = buffer.getLong(offset + FILE_ID_BOTH_DIRECTORY_INFO_FILE_ID);
        long size = Math.max(0, buffer.getLong(offset + FILE_ID_BOTH_DIRECTORY_INFO_END_OF_FILE));
        long lastWriteTime = buffer.getLong(offset + FILE_ID_BOTH_DIRECTORY_INFO_LAST_WRITE_TIME);
        boolean directory = (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
        boolean reparse = (attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
        entries.add(new DirectoryEntry(
            name, new NativeFileIdentity(0, directoryFileId(fileId)), directory, !directory && !reparse,
            reparse, size, toInstant(lastWriteTime)));
        if (next == 0) {
          break;
        }
        if (next < 0 || offset + next >= FILE_ID_BOTH_DIRECTORY_INFO_BUFFER) {
          throw new NativeBoundaryException("native directory record is invalid", 0);
        }
        offset += next;
      }
    }
  }

  @Override
  public int read(NativeHandle nativeHandle, byte[] buffer, int offset, int length) {
    if (length == 0) {
      return 0;
    }
    byte[] target = offset == 0 && length == buffer.length ? buffer : new byte[length];
    IntByReference read = new IntByReference();
    if (!Kernel32.INSTANCE.ReadFile(nativeHandle(nativeHandle), target, target.length, read, null)) {
      int error = Kernel32.INSTANCE.GetLastError();
      if (error == ERROR_HANDLE_EOF) {
        return -1;
      }
      throw new NativeBoundaryException("native file read failed", ntStatusForWin32(error));
    }
    int count = read.getValue();
    if (count == 0) {
      return -1;
    }
    if (target != buffer) {
      System.arraycopy(target, 0, buffer, offset, count);
    }
    return count;
  }

  @Override
  public long seek(NativeHandle nativeHandle, long offset) {
    if (offset < 0) {
      throw new NativeBoundaryException("native seek failed", 0);
    }
    LongByReference position = new LongByReference();
    if (!kernel32Ext().SetFilePointerEx(nativeHandle(nativeHandle), offset, position, FILE_BEGIN)) {
      throw win32Failure("native seek failed");
    }
    return position.getValue();
  }

  @Override
  public void close(NativeHandle nativeHandle) {
    if (!Kernel32.INSTANCE.CloseHandle(nativeHandle(nativeHandle))) {
      throw win32Failure("native handle close failed");
    }
  }

  private NativeHandle open(String objectName, HANDLE root, OpenKind kind, int objectAttributes) {
    UnicodeString name = new UnicodeString(objectName);
    ObjectAttributes attributes = new ObjectAttributes(root, name.getPointer(), objectAttributes);
    IoStatusBlock statusBlock = new IoStatusBlock();
    HANDLEByReference result = new HANDLEByReference();
    int access = kind == OpenKind.DIRECTORY
        ? FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | SYNCHRONIZE
        : FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE;
    int options = FILE_SYNCHRONOUS_IO_NONALERT
        | (kind == OpenKind.DIRECTORY ? FILE_DIRECTORY_FILE : FILE_NON_DIRECTORY_FILE);
    int status = ntDll().NtCreateFile(
        result, access, attributes, statusBlock, null, FILE_ATTRIBUTE_NORMAL,
        FILE_SHARE_READ_WRITE_DELETE, FILE_OPEN, options, null, 0);
    if (status != 0) {
      throw new NativeBoundaryException("native relative open failed", status);
    }
    HANDLE handle = result.getValue();
    if (handle == null || handle.getPointer() == null) {
      throw new NativeBoundaryException("native relative open failed", 0);
    }
    return new NativeHandle(handle);
  }

  private NtDll ntDll() {
    NtDll result = ntDll;
    if (result == null) {
      result = Native.load("ntdll", NtDll.class);
      ntDll = result;
    }
    return result;
  }

  private Kernel32Ext kernel32Ext() {
    Kernel32Ext result = kernel32Ext;
    if (result == null) {
      result = Native.load("kernel32", Kernel32Ext.class);
      kernel32Ext = result;
    }
    return result;
  }

  private NativeBoundaryException win32Failure(String message) {
    return new NativeBoundaryException(message, ntStatusForWin32(Kernel32.INSTANCE.GetLastError()));
  }

  private int ntStatusForWin32(int error) {
    // RtlNtStatusToDosError is bound so callers can keep raw NTSTATUS internally. Win32 errors do
    // not have a reliable inverse mapping; zero still maps to generic denial at the boundary.
    ntDll().RtlNtStatusToDosError(0);
    return error;
  }

  private HANDLE nativeHandle(NativeHandle handle) {
    if (!(handle.value() instanceof HANDLE nativeHandle)) {
      throw new NativeBoundaryException("native handle is invalid", 0);
    }
    return nativeHandle;
  }

  private String ntPath(String absolutePath) {
    if (absolutePath.startsWith("\\\\")) {
      return "\\??\\UNC\\" + absolutePath.substring(2);
    }
    return "\\??\\" + absolutePath;
  }

  private byte[] fileIdBytes(WinBase.FILE_ID_INFO id) {
    byte[] bytes = new byte[16];
    for (int index = 0; index < bytes.length; index++) {
      bytes[index] = id.FileId.Identifier[index].byteValue();
    }
    return bytes;
  }

  private byte[] directoryFileId(long fileId) {
    return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(fileId).array();
  }

  private Instant toInstant(long fileTime) {
    long unixHundredNanoseconds = fileTime - FILETIME_UNIX_EPOCH;
    long seconds = Math.floorDiv(unixHundredNanoseconds, HUNDRED_NANOSECONDS_PER_SECOND);
    long nanos = Math.floorMod(unixHundredNanoseconds, HUNDRED_NANOSECONDS_PER_SECOND) * 100;
    return Instant.ofEpochSecond(seconds, nanos);
  }

  /** Custom ntdll binding because jna-platform does not expose NtCreateFile. */
  private interface NtDll extends StdCallLibrary {
    int NtCreateFile(
        HANDLEByReference fileHandle,
        int desiredAccess,
        ObjectAttributes objectAttributes,
        IoStatusBlock ioStatusBlock,
        Pointer allocationSize,
        int fileAttributes,
        int shareAccess,
        int createDisposition,
        int createOptions,
        Pointer eaBuffer,
        int eaLength);

    int RtlNtStatusToDosError(int status);
  }

  /** Custom kernel binding because jna-platform's Kernel32 interface lacks SetFilePointerEx. */
  private interface Kernel32Ext extends StdCallLibrary {
    boolean SetFilePointerEx(HANDLE file, long distance, LongByReference newFilePointer, int moveMethod);
  }

  /** Native UNICODE_STRING; the backing memory stays reachable for the NtCreateFile invocation. */
  public static final class UnicodeString extends Structure {
    public short Length;
    public short MaximumLength;
    public Pointer Buffer;
    private Memory backing;

    public UnicodeString(String value) {
      backing = new Memory(((long) value.length() + 1) * Native.WCHAR_SIZE);
      backing.setWideString(0, value);
      Length = (short) (value.length() * Native.WCHAR_SIZE);
      MaximumLength = (short) (Length + Native.WCHAR_SIZE);
      Buffer = backing;
      write();
    }

    @Override
    protected List<String> getFieldOrder() {
      return List.of("Length", "MaximumLength", "Buffer");
    }
  }

  /** Native OBJECT_ATTRIBUTES used to supply a trusted RootDirectory and OBJ_DONT_REPARSE. */
  public static final class ObjectAttributes extends Structure {
    public int Length;
    public HANDLE RootDirectory;
    public Pointer ObjectName;
    public int Attributes;
    public Pointer SecurityDescriptor;
    public Pointer SecurityQualityOfService;

    public ObjectAttributes(HANDLE rootDirectory, Pointer objectName, int attributes) {
      RootDirectory = rootDirectory;
      ObjectName = objectName;
      Attributes = attributes;
      Length = size();
      write();
    }

    @Override
    protected List<String> getFieldOrder() {
      return List.of("Length", "RootDirectory", "ObjectName", "Attributes",
          "SecurityDescriptor", "SecurityQualityOfService");
    }
  }

  /** Native IO_STATUS_BLOCK storage for NtCreateFile. */
  public static final class IoStatusBlock extends Structure {
    public Pointer StatusPointer;
    public Pointer Information;

    @Override
    protected List<String> getFieldOrder() {
      return List.of("StatusPointer", "Information");
    }
  }
}

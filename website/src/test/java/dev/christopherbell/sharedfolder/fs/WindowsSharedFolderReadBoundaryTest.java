package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class WindowsSharedFolderReadBoundaryTest {
  private static final Instant MODIFIED = Instant.parse("2026-07-17T12:00:00Z");

  @Test
  void rootAndRelativeOpensAlwaysRequestDontReparse() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);

    boundary.list("music");

    assertThat(bridge.openRequests()).isNotEmpty();
    assertThat(bridge.openRequests()).allSatisfy(request ->
        assertThat(request.objectAttributes() & WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE)
            .isNotZero());
    assertThat(bridge.openRequests()).allSatisfy(request ->
        assertThat(request.objectAttributes() & WindowsSharedFolderNativeBridge.OBJ_CASE_INSENSITIVE)
            .isNotZero());
  }

  @Test
  void reparseNtStatusMapsToGenericBoundaryDenial() {
    RecordingBridge bridge = new RecordingBridge();
    bridge.failOpenWith(WindowsSharedFolderNativeBridge.STATUS_REPARSE_POINT_ENCOUNTERED);
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);

    assertThatThrownBy(() -> boundary.list("music"))
        .isInstanceOf(UnsafeSharedPathException.class)
        .hasMessage("Shared-folder item is not available");
  }

  @Test
  void delayedResourceRejectsChangedFileIdAndClosesTheActualHandle() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);
    WindowsSharedFolderReadBoundary.NativeReadTarget target = boundary.file("letter.txt");
    bridge.setNextFileIdentity(new WindowsSharedFolderNativeBridge.NativeFileIdentity(7, identityBytes(9)));

    assertThatThrownBy(() -> target.resource("letter.txt").getInputStream())
        .isInstanceOf(FileNotFoundException.class)
        .hasMessage("Shared-folder item is no longer available");
    assertThat(bridge.closedHandles()).contains(bridge.lastOpenedHandle());
  }

  @Test
  void delayedResourceAfterBoundaryShutdownMapsRootLossToGenericNotFound() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);
    var resource = boundary.file("letter.txt").resource("letter.txt");

    boundary.destroy();

    assertThatThrownBy(resource::getInputStream)
        .isInstanceOf(FileNotFoundException.class)
        .hasMessage("Shared-folder item is no longer available");
  }

  @Test
  void enumerationUsesTheOpenedDirectoryHandleAndSkipsUnsafeChildren() {
    RecordingBridge bridge = new RecordingBridge();
    bridge.setEntries(List.of(
        new WindowsSharedFolderNativeBridge.DirectoryEntry("good.txt", fileIdentity(), false, true,
            false, 3, MODIFIED),
        new WindowsSharedFolderNativeBridge.DirectoryEntry("junction", fileIdentity(), true, false,
            true, 0, MODIFIED),
        new WindowsSharedFolderNativeBridge.DirectoryEntry("..", fileIdentity(), false, true,
            false, 3, MODIFIED)));
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);

    var entries = boundary.list("");

    assertThat(entries).extracting(entry -> entry.name()).containsExactly("good.txt");
    assertThat(entries.getFirst().size()).isEqualTo(3);
    assertThat(entries.getFirst().modifiedAt()).isEqualTo(MODIFIED);
    assertThat(bridge.listedHandles()).containsExactly(bridge.rootHandle());
    assertThat(bridge.openRequests()).hasSize(1);
  }

  @Test
  void nativeStreamSupportsSkipRangesAndIdempotentClose() throws Exception {
    RecordingBridge bridge = new RecordingBridge();
    bridge.setBytes("abcdef".getBytes());
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);

    InputStream stream = boundary.file("letter.txt").resource("letter.txt").getInputStream();
    assertThat(stream.skip(2)).isEqualTo(2);
    assertThat(stream.read()).isEqualTo((int) 'c');
    stream.close();
    stream.close();

    assertThat(bridge.closeCount(bridge.lastOpenedHandle())).isEqualTo(1);
  }

  @Test
  void failedRelativeOpenClosesAlreadyOpenedDirectoryHandles() {
    RecordingBridge bridge = new RecordingBridge();
    bridge.failOnName("letter.txt");
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);

    assertThatThrownBy(() -> boundary.file("music/letter.txt"))
        .isInstanceOf(UnsafeSharedPathException.class);

    assertThat(bridge.closedHandles()).contains(bridge.handleFor("music"));
  }

  @Test
  void nativeTextPreviewUsesBoundedBlockReadsInsteadOfOneReadFileCallPerByte() {
    RecordingBridge bridge = new RecordingBridge();
    bridge.setBytes("x".repeat(64 * 1024).getBytes());
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);
    SharedFolderPreviewService previews = new SharedFolderPreviewService(properties(), boundary);

    var preview = previews.open("letter.txt");

    assertThat(preview.text()).hasSize(64 * 1024);
    assertThat(bridge.readCalls()).isLessThan(20);
  }

  @Test
  void nativeModeRemainsFailClosedWhenTheHeldRootIsNoLongerAvailable() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);

    boundary.destroy();

    assertThat(boundary.nativeMode()).isTrue();
    assertThat(boundary.active()).isFalse();
    assertThatThrownBy(() -> boundary.list(""))
        .isInstanceOf(UnsafeSharedPathException.class)
        .hasMessage("Shared-folder item is not available");
  }

  @Test
  void failedNativeStreamCloseIsMappedAndNeverRetried() throws Exception {
    RecordingBridge bridge = new RecordingBridge();
    bridge.failClose();
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(
        Path.of("C:/shared"), bridge);
    InputStream stream = boundary.file("letter.txt").resource("letter.txt").getInputStream();
    WindowsSharedFolderNativeBridge.NativeHandle handle = bridge.lastOpenedHandle();

    assertThatThrownBy(stream::close)
        .isInstanceOf(java.io.IOException.class)
        .hasMessage("Shared-folder item is no longer available");
    stream.close();

    assertThat(bridge.closeCount(handle)).isEqualTo(1);
  }

  private static WindowsSharedFolderNativeBridge.NativeFileIdentity fileIdentity() {
    return new WindowsSharedFolderNativeBridge.NativeFileIdentity(7, identityBytes(1));
  }

  private static byte[] identityBytes(int first) {
    byte[] bytes = new byte[16];
    bytes[0] = (byte) first;
    return bytes;
  }

  private static SharedFolderProperties properties() {
    return new SharedFolderProperties(
        Path.of("C:/shared"), Path.of("C:/system"), DataSize.ofGigabytes(1), DataSize.ofMegabytes(1),
        DataSize.ofGigabytes(1), DataSize.ofGigabytes(1), Duration.ofDays(1), Duration.ofDays(1), true);
  }

  private record OpenRequest(
      WindowsSharedFolderNativeBridge.NativeHandle parent,
      String name,
      WindowsSharedFolderNativeBridge.OpenKind kind,
      int objectAttributes) {}

  private static final class RecordingBridge implements WindowsSharedFolderNativeBridge {
    private final NativeHandle root = new NativeHandle("root");
    private final List<OpenRequest> openRequests = new ArrayList<>();
    private final List<NativeHandle> closedHandles = new ArrayList<>();
    private final List<NativeHandle> listedHandles = new ArrayList<>();
    private final Map<NativeHandle, Integer> positions = new HashMap<>();
    private final Map<String, NativeHandle> handlesByName = new HashMap<>();
    private final Map<NativeHandle, Integer> closeCounts = new HashMap<>();
    private List<DirectoryEntry> entries = List.of();
    private byte[] bytes = new byte[0];
    private int failureStatus;
    private String failName;
    private NativeFileIdentity nextFileIdentity;
    private NativeHandle lastOpenedHandle;
    private int readCalls;
    private boolean failClose;

    @Override
    public NativeHandle openRoot(Path rootPath, int objectAttributes) {
      openRequests.add(new OpenRequest(null, rootPath.toString(), OpenKind.DIRECTORY, objectAttributes));
      return root;
    }

    @Override
    public NativeHandle openRelative(
        NativeHandle parent, String name, OpenKind kind, int objectAttributes) {
      if (parent == null) {
        throw new NullPointerException("retained root was closed");
      }
      openRequests.add(new OpenRequest(parent, name, kind, objectAttributes));
      if (failureStatus != 0 || name.equals(failName)) {
        throw new NativeBoundaryException("native open failed", failureStatus);
      }
      NativeHandle handle = new NativeHandle(name + "-" + openRequests.size());
      handlesByName.put(name, handle);
      lastOpenedHandle = handle;
      return handle;
    }

    @Override
    public NativeFileMetadata metadata(NativeHandle handle) {
      if (handle.equals(root) || handle.value().toString().startsWith("music")) {
        return new NativeFileMetadata(fileIdentity(), true, false, 0, MODIFIED);
      }
      NativeFileIdentity identity = nextFileIdentity == null ? fileIdentity() : nextFileIdentity;
      nextFileIdentity = null;
      return new NativeFileMetadata(identity, false, true, bytes.length, MODIFIED);
    }

    @Override
    public List<DirectoryEntry> listDirectory(NativeHandle directory) {
      listedHandles.add(directory);
      return entries;
    }

    @Override
    public int read(NativeHandle handle, byte[] buffer, int offset, int length) {
      readCalls++;
      int position = positions.getOrDefault(handle, 0);
      if (position >= bytes.length) {
        return -1;
      }
      int count = Math.min(length, bytes.length - position);
      System.arraycopy(bytes, position, buffer, offset, count);
      positions.put(handle, position + count);
      return count;
    }

    @Override
    public long seek(NativeHandle handle, long offset) {
      positions.put(handle, (int) Math.min(bytes.length, offset));
      return positions.get(handle);
    }

    @Override
    public void close(NativeHandle handle) {
      closedHandles.add(handle);
      closeCounts.merge(handle, 1, Integer::sum);
      if (failClose) {
        throw new NativeBoundaryException("native close failed", 0);
      }
    }

    List<OpenRequest> openRequests() { return openRequests; }
    List<NativeHandle> closedHandles() { return closedHandles; }
    List<NativeHandle> listedHandles() { return listedHandles; }
    NativeHandle rootHandle() { return root; }
    NativeHandle lastOpenedHandle() { return lastOpenedHandle; }
    NativeHandle handleFor(String name) { return handlesByName.get(name); }
    int closeCount(NativeHandle handle) { return closeCounts.getOrDefault(handle, 0); }
    int readCalls() { return readCalls; }
    void failOpenWith(int status) { failureStatus = status; }
    void failOnName(String name) { failName = name; }
    void setEntries(List<DirectoryEntry> values) { entries = values; }
    void setBytes(byte[] value) { bytes = value; }
    void setNextFileIdentity(NativeFileIdentity value) { nextFileIdentity = value; }
    void failClose() { failClose = true; }
  }
}

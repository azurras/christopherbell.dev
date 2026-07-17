package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsSharedFolderMutationBoundaryTest {
  private static final Instant MODIFIED = Instant.parse("2026-07-17T12:00:00Z");

  @Test
  void createsAndRenamesOnlyThroughRetainedHandlesWithDontReparse() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);

    boundary.createDirectory("documents", "new-folder");
    boundary.rename("documents/old.txt", "documents", "new.txt", false);

    assertThat(bridge.openRequests).allSatisfy(request ->
        assertThat(request.attributes & WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE).isNotZero());
    assertThat(bridge.createdParents).allSatisfy(parent ->
        assertThat((String) parent.value()).doesNotContain("C:"));
    assertThat(bridge.renames).containsExactly(new Rename("old.txt", "new.txt", false));
    boundary.destroy();
    assertThat(bridge.closed).contains("visible-root", "system-root");
  }

  @Test
  void stagesWritesFlushesAndFinalizesAcrossRetainedSameVolumeRoots() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);

    try (var staging = boundary.createStaging("11111111-1111-1111-1111-111111111111")) {
      assertThat(staging.write(new byte[] {1, 2, 3}, 0, 3)).isEqualTo(3);
      staging.flush();
      assertThat(staging.metadata().identity().volumeSerial()).isEqualTo(7);
    }
    NativeFileMetadata finalized = boundary.finalizeStaging(
        "11111111-1111-1111-1111-111111111111", "documents", "upload.bin", false);
    boundary.delete("documents/upload.bin");
    boundary.destroy();

    assertThat(finalized.identity().volumeSerial()).isEqualTo(7);
    assertThat(bridge.writes).containsExactly("11111111-1111-1111-1111-111111111111:3");
    assertThat(bridge.flushed).containsExactly("11111111-1111-1111-1111-111111111111");
    assertThat(bridge.renames).contains(new Rename(
        "11111111-1111-1111-1111-111111111111", "upload.bin", false));
    assertThat(bridge.deleted).containsExactly("upload.bin");
    assertThat(bridge.openRequests).allSatisfy(request ->
        assertThat(request.attributes & WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE).isNotZero());
    assertThat(bridge.closed).contains(
        "shared-folder-upload-staging", "system-root", "visible-root");
  }

  @Test
  void rejectsCrossVolumeFinalizeAndClosesEveryTransientHandle() {
    RecordingBridge bridge = new RecordingBridge();
    bridge.stagingVolume = 9;
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("D:/system"), bridge);
    boundary.createStaging("22222222-2222-2222-2222-222222222222").close();

    assertThatThrownBy(() -> boundary.finalizeStaging(
        "22222222-2222-2222-2222-222222222222", "documents", "upload.bin", false))
        .isInstanceOf(NativeBoundaryException.class)
        .hasMessageContaining("different volumes");

    assertThat(bridge.closed).contains(
        "22222222-2222-2222-2222-222222222222", "documents");
    assertThat(bridge.renames).isEmpty();
    boundary.destroy();
  }

  @Test
  void refusesRenameWhenTheHeldMutationHandleNoLongerMatchesTheObservation() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);
    NativeFileMetadata observed = boundary.metadata("documents/old.txt");
    bridge.changeIdentityOnMutationOpen = true;

    assertThatThrownBy(() -> boundary.rename(
        "documents/old.txt", "documents", "new.txt", false, observed))
        .isInstanceOf(NativeBoundaryException.class)
        .hasMessageContaining("changed");

    assertThat(bridge.renames).isEmpty();
    boundary.destroy();
  }

  @Test
  void refusesExplicitReplacementWhenTheTargetChangesDuringTheFinalRecheck() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);
    NativeFileMetadata source = boundary.metadata("documents/old.txt");
    NativeFileMetadata target = boundary.metadata("documents/target.txt");
    bridge.changeTargetIdentityOnNextOpen = true;

    assertThatThrownBy(() -> boundary.rename(
        "documents/old.txt", "documents", "target.txt", true, source, target))
        .isInstanceOf(NativeBoundaryException.class)
        .hasMessageContaining("changed");

    assertThat(bridge.renames).isEmpty();
    boundary.destroy();
  }

  @Test
  void reserveQueriesUseTheRetainedSystemRootVolumeHandle() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);

    assertThat(boundary.usableSystemBytes()).isEqualTo(987_654_321L);
    assertThat(bridge.usableSpaceHandles).containsExactly("system-root");
    boundary.destroy();
  }

  private record OpenRequest(WindowsSharedFolderNativeBridge.NativeHandle parent, int attributes) {}
  private record Rename(String sourceName, String targetName, boolean replace) {}

  private static final class RecordingBridge implements WindowsSharedFolderNativeBridge {
    private final NativeHandle visibleRoot = new NativeHandle("visible-root");
    private final NativeHandle systemRoot = new NativeHandle("system-root");
    private final List<OpenRequest> openRequests = new ArrayList<>();
    private final List<NativeHandle> createdParents = new ArrayList<>();
    private final List<Rename> renames = new ArrayList<>();
    private final List<String> writes = new ArrayList<>();
    private final List<String> flushed = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private final List<String> usableSpaceHandles = new ArrayList<>();
    private long stagingVolume = 7;
    private boolean changeIdentityOnMutationOpen;
    private boolean mutationSourceOpened;
    private boolean changeTargetIdentityOnNextOpen;
    private boolean targetRaced;

    @Override
    public NativeHandle openRoot(Path rootPath, int attributes) {
      openRequests.add(new OpenRequest(null, attributes));
      return rootPath.toString().contains("system") ? systemRoot : visibleRoot;
    }

    @Override
    public NativeHandle openRelative(NativeHandle parent, String name, OpenKind kind, int attributes) {
      openRequests.add(new OpenRequest(parent, attributes));
      if (changeTargetIdentityOnNextOpen && name.equals("target.txt")) {
        targetRaced = true;
        changeTargetIdentityOnNextOpen = false;
      }
      return new NativeHandle(name);
    }

    @Override
    public NativeHandle openRelativeForMutation(
        NativeHandle parent, String name, OpenKind kind, int attributes) {
      if (changeIdentityOnMutationOpen && name.equals("old.txt")) {
        mutationSourceOpened = true;
      }
      return openRelative(parent, name, kind, attributes);
    }

    @Override
    public NativeHandle createRelative(NativeHandle parent, String name, OpenKind kind, int attributes) {
      openRequests.add(new OpenRequest(parent, attributes));
      createdParents.add(parent);
      return new NativeHandle(name);
    }

    @Override
    public void rename(NativeHandle source, NativeHandle destinationParent, String name, boolean replace) {
      renames.add(new Rename(source.value().toString(), name, replace));
    }

    @Override
    public NativeFileMetadata metadata(NativeHandle handle) {
      boolean directory = !handle.value().toString().contains(".")
          && !handle.value().toString().matches("[0-9a-f-]{36}");
      long volume = handle.value().toString().matches("[0-9a-f-]{36}") ? stagingVolume : 7;
      byte identityByte = mutationSourceOpened && handle.value().equals("old.txt")
          || targetRaced && handle.value().equals("target.txt") ? (byte) 1 : 0;
      return new NativeFileMetadata(identity(volume, identityByte), directory, !directory, 0, MODIFIED);
    }

    @Override public List<DirectoryEntry> listDirectory(NativeHandle directory) { return List.of(); }
    @Override public int read(NativeHandle handle, byte[] buffer, int offset, int length) { return -1; }
    @Override public long seek(NativeHandle handle, long offset) { return offset; }
    @Override public int write(NativeHandle handle, byte[] buffer, int offset, int length) {
      writes.add(handle.value() + ":" + length);
      return length;
    }
    @Override public void flush(NativeHandle handle) { flushed.add(handle.value().toString()); }
    @Override public void delete(NativeHandle handle) { deleted.add(handle.value().toString()); }
    @Override public long usableSpace(NativeHandle handle) {
      usableSpaceHandles.add(handle.value().toString());
      return 987_654_321L;
    }
    @Override public void close(NativeHandle handle) { closed.add(handle.value().toString()); }

    private NativeFileIdentity identity(long volume, byte firstByte) {
      byte[] fileId = new byte[16];
      fileId[0] = firstByte;
      return new NativeFileIdentity(volume, fileId);
    }
  }
}

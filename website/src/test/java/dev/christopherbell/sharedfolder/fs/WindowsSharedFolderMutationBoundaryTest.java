package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.DirectoryEntry;
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
  void mutationRootsAndCompleteAncestorChainsDenyDeleteSharingUntilRenameCompletes() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);

    boundary.rename("documents/archive/old.txt", "documents/destination", "new.txt", false);

    assertThat(bridge.mutationRoots)
        .containsExactly(Path.of("C:/shared"), Path.of("C:/system"));
    assertThat(bridge.mutationOpenNames)
        .contains("documents", "archive", "old.txt", "destination");
    int rename = bridge.events.indexOf("rename:old.txt:new.txt");
    assertThat(rename).isGreaterThanOrEqualTo(0);
    assertThat(bridge.events.indexOf("close:documents")).isGreaterThan(rename);
    assertThat(bridge.events.indexOf("close:archive")).isGreaterThan(rename);
    assertThat(bridge.events.indexOf("close:destination")).isGreaterThan(rename);
    boundary.destroy();
  }

  @Test
  void finalVisibleAndStagingMutationLeavesUseExclusiveWriteAndDeleteDenial() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);

    NativeFileMetadata source = boundary.metadata("documents/source.txt");
    NativeFileMetadata target = boundary.metadata("documents/target.txt");
    boundary.rename("documents/source.txt", "documents", "target.txt", true, source, target);
    boundary.createStaging("55555555-5555-5555-5555-555555555555").close();
    boundary.staging("55555555-5555-5555-5555-555555555555").close();
    boundary.finalizeStaging(
        "55555555-5555-5555-5555-555555555555", "documents", "upload.bin", false);

    assertThat(bridge.exclusiveMutationOpenNames)
        .contains("source.txt", "target.txt", "55555555-5555-5555-5555-555555555555");
    assertThat(java.util.Collections.frequency(
        bridge.exclusiveMutationOpenNames, "55555555-5555-5555-5555-555555555555"))
        .isEqualTo(2);
    assertThat(bridge.mutationOpenNames).contains("documents");
    boundary.destroy();
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
  void explicitReplacementQuarantinesThePinnedTargetBeforeANoReplaceSourceMove() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);
    NativeFileMetadata source = boundary.metadata("documents/old.txt");
    NativeFileMetadata target = boundary.metadata("documents/target.txt");

    boundary.rename("documents/old.txt", "documents", "target.txt", true, source, target);

    assertThat(bridge.renames).hasSize(2);
    assertThat(bridge.renames.get(0).sourceName()).isEqualTo("target.txt");
    assertThat(bridge.renames.get(0).replace()).isFalse();
    assertThat(bridge.renames.get(0).targetName()).matches("[0-9a-f-]{36}");
    assertThat(bridge.renames.get(1)).isEqualTo(new Rename("old.txt", "target.txt", false));
    assertThat(bridge.deleted).contains("target.txt");
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

  @Test
  void recycleMovesAndRestoresTheExactObservedLeafThroughRetainedPrivateHandles() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);
    NativeFileMetadata observed = boundary.metadata("documents/report.pdf");
    String key = "99999999-9999-9999-9999-999999999999";

    NativeFileMetadata recycled = boundary.recycleVisible("documents/report.pdf", key, observed);
    assertThat(boundary.recycleMetadata(key).identity().sameFile(recycled.identity())).isTrue();
    NativeFileMetadata restored = boundary.restoreRecycle(
        key, "documents", "report.pdf", recycled, false, null);

    assertThat(restored.identity().sameFile(recycled.identity())).isTrue();
    assertThat(bridge.renames).containsExactly(
        new Rename("report.pdf", key, false),
        new Rename(key, "report.pdf", false));
    assertThat(bridge.exclusiveMutationOpenNames).contains("report.pdf", key);
    assertThat(bridge.mutationOpenNames).contains("shared-folder-recycle");
    boundary.destroy();
    assertThat(bridge.closed).contains("shared-folder-recycle");
  }

  @Test
  void recycleRestoreReplacementPinsAndDisplacesTheObservedCurrentTarget() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);
    String key = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    NativeFileMetadata recycled = boundary.metadata(key);
    NativeFileMetadata target = boundary.metadata("documents/report.pdf");

    boundary.restoreRecycle(key, "documents", "report.pdf", recycled, true, target);

    assertThat(bridge.renames).hasSize(2);
    assertThat(bridge.renames.get(0).sourceName()).isEqualTo("report.pdf");
    assertThat(bridge.renames.get(0).targetName()).matches("[0-9a-f-]{36}");
    assertThat(bridge.renames.get(1)).isEqualTo(new Rename(key, "report.pdf", false));
    assertThat(bridge.deleted).contains("report.pdf");
    boundary.destroy();
  }

  @Test
  void restoreClosesAlreadyOpenedHandlesWhenTheObservedTargetOpenRaces() {
    RecordingBridge bridge = new RecordingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);
    String key = "abababab-abab-abab-abab-abababababab";
    NativeFileMetadata recycled = boundary.metadata(key);
    NativeFileMetadata target = boundary.metadata("documents/report.pdf");
    int sourceClosesBefore = java.util.Collections.frequency(bridge.closed, key);
    int destinationClosesBefore = java.util.Collections.frequency(bridge.closed, "documents");
    bridge.failExclusiveMutationOpenName = "report.pdf";

    assertThatThrownBy(() -> boundary.restoreRecycle(
        key, "documents", "report.pdf", recycled, true, target, key))
        .isInstanceOf(NativeBoundaryException.class)
        .hasMessageContaining("raced");

    assertThat(java.util.Collections.frequency(bridge.closed, key))
        .isEqualTo(sourceClosesBefore + 1);
    assertThat(java.util.Collections.frequency(bridge.closed, "documents"))
        .isEqualTo(destinationClosesBefore + 2);
    boundary.destroy();
  }

  @Test
  void recursiveRecyclePurgeDeletesChildrenThroughRetainedHandlesBeforeTheParent() {
    RecordingBridge bridge = new RecordingBridge();
    String key = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    bridge.directoryHandles.add(key);
    bridge.mutateDirectoryMetadataAfterChildDelete = true;
    bridge.directoryChildren.put(key, List.of(new DirectoryEntry(
        "child.txt", bridge.identity(7, (byte) 0), false, true, false, 0, MODIFIED)));
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), bridge);

    NativeFileMetadata expected = boundary.recycleMetadata(key);
    boundary.deleteRecycleTree(key, expected);

    assertThat(bridge.deleted).containsSubsequence("child.txt", key);
    boundary.destroy();
  }

  @Test
  void privateDirectoryInitializationCreatesOnlyAfterAnExactMissingStatus() {
    RecordingBridge unavailableBridge = new RecordingBridge();
    NativeBoundaryException unavailableFailure =
        new NativeBoundaryException("private directory unavailable", 0);
    unavailableBridge.privateDirectoryOpenFailure = unavailableFailure;
    WindowsSharedFolderMutationBoundary unavailable = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), unavailableBridge);

    assertThatThrownBy(() -> unavailable.createStaging(
        "77777777-7777-7777-7777-777777777777"))
        .isSameAs(unavailableFailure);
    assertThat(unavailableBridge.createdNames).doesNotContain("shared-folder-upload-staging");
    unavailable.destroy();

    RecordingBridge missingBridge = new RecordingBridge();
    missingBridge.privateDirectoryOpenFailure =
        new NativeBoundaryException("private directory missing", 0xC0000034);
    WindowsSharedFolderMutationBoundary missing = WindowsSharedFolderMutationBoundary.forTest(
        Path.of("C:/shared"), Path.of("C:/system"), missingBridge);
    missing.createStaging("88888888-8888-8888-8888-888888888888").close();

    assertThat(missingBridge.createdNames).contains("shared-folder-upload-staging");
    missing.destroy();
  }

  private record OpenRequest(WindowsSharedFolderNativeBridge.NativeHandle parent, int attributes) {}
  private record Rename(String sourceName, String targetName, boolean replace) {}

  private static final class RecordingBridge implements WindowsSharedFolderNativeBridge {
    private final NativeHandle visibleRoot = new NativeHandle("visible-root");
    private final NativeHandle systemRoot = new NativeHandle("system-root");
    private final List<OpenRequest> openRequests = new ArrayList<>();
    private final List<NativeHandle> createdParents = new ArrayList<>();
    private final List<String> createdNames = new ArrayList<>();
    private final List<Rename> renames = new ArrayList<>();
    private final List<String> writes = new ArrayList<>();
    private final List<String> flushed = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private final List<String> usableSpaceHandles = new ArrayList<>();
    private final List<Path> mutationRoots = new ArrayList<>();
    private final List<String> mutationOpenNames = new ArrayList<>();
    private final List<String> exclusiveMutationOpenNames = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private long stagingVolume = 7;
    private boolean changeIdentityOnMutationOpen;
    private boolean mutationSourceOpened;
    private boolean changeTargetIdentityOnNextOpen;
    private boolean targetRaced;
    private boolean mutateDirectoryMetadataAfterChildDelete;
    private String failExclusiveMutationOpenName;
    private NativeBoundaryException privateDirectoryOpenFailure;
    private final java.util.Set<String> directoryHandles = new java.util.HashSet<>();
    private final java.util.Map<String, List<DirectoryEntry>> directoryChildren =
        new java.util.HashMap<>();

    @Override
    public NativeHandle openRoot(Path rootPath, int attributes) {
      openRequests.add(new OpenRequest(null, attributes));
      return rootPath.toString().contains("system") ? systemRoot : visibleRoot;
    }

    @Override
    public NativeHandle openRootForMutation(Path rootPath, int attributes) {
      mutationRoots.add(rootPath);
      return openRoot(rootPath, attributes);
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
    public NativeHandle openRelativePinned(
        NativeHandle parent, String name, OpenKind kind, int attributes) {
      mutationOpenNames.add(name);
      return openRelative(parent, name, kind, attributes);
    }

    @Override
    public NativeHandle openRelativeForMutation(
        NativeHandle parent, String name, OpenKind kind, int attributes) {
      mutationOpenNames.add(name);
      if (privateDirectoryOpenFailure != null
          && (name.equals("shared-folder-upload-staging")
              || name.equals("shared-folder-mutation-quarantine")
              || name.equals("shared-folder-recycle"))) {
        NativeBoundaryException failure = privateDirectoryOpenFailure;
        privateDirectoryOpenFailure = null;
        throw failure;
      }
      if (changeIdentityOnMutationOpen && name.equals("old.txt")) {
        mutationSourceOpened = true;
      }
      return openRelative(parent, name, kind, attributes);
    }

    @Override
    public NativeHandle openRelativeForExclusiveMutation(
        NativeHandle parent, String name, OpenKind kind, int attributes) {
      exclusiveMutationOpenNames.add(name);
      if (name.equals(failExclusiveMutationOpenName)) {
        throw NativeBoundaryException.conflict("observed target raced");
      }
      return openRelativeForMutation(parent, name, kind, attributes);
    }

    @Override
    public NativeHandle createRelative(NativeHandle parent, String name, OpenKind kind, int attributes) {
      openRequests.add(new OpenRequest(parent, attributes));
      createdParents.add(parent);
      createdNames.add(name);
      return new NativeHandle(name);
    }

    @Override
    public void rename(NativeHandle source, NativeHandle destinationParent, String name, boolean replace) {
      events.add("rename:" + source.value() + ":" + name);
      renames.add(new Rename(source.value().toString(), name, replace));
    }

    @Override
    public NativeFileMetadata metadata(NativeHandle handle) {
      boolean directory = directoryHandles.contains(handle.value().toString())
          || !handle.value().toString().contains(".")
              && !handle.value().toString().matches("[0-9a-f-]{36}");
      long volume = handle.value().toString().matches("[0-9a-f-]{36}") ? stagingVolume : 7;
      byte identityByte = mutationSourceOpened && handle.value().equals("old.txt")
          || targetRaced && handle.value().equals("target.txt") ? (byte) 1 : 0;
      boolean changedDirectory = mutateDirectoryMetadataAfterChildDelete && directory
          && !deleted.isEmpty();
      return new NativeFileMetadata(
          identity(volume, identityByte), directory, !directory,
          changedDirectory ? deleted.size() : 0,
          changedDirectory ? MODIFIED.plusSeconds(1) : MODIFIED);
    }

    @Override public List<DirectoryEntry> listDirectory(NativeHandle directory) {
      return directoryChildren.getOrDefault(directory.value().toString(), List.of());
    }
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
    @Override public void close(NativeHandle handle) {
      events.add("close:" + handle.value());
      closed.add(handle.value().toString());
    }

    private NativeFileIdentity identity(long volume, byte firstByte) {
      byte[] fileId = new byte[16];
      fileId[0] = firstByte;
      return new NativeFileIdentity(volume, fileId);
    }
  }
}

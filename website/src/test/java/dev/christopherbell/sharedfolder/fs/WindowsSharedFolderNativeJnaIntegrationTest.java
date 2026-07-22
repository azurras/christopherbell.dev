package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeHandle;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.configuration.SharedFolderProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.util.unit.DataSize;

/** Windows-only checks for JNA structure layout and calling conventions against the real NT APIs. */
@EnabledOnOs(OS.WINDOWS)
class WindowsSharedFolderNativeJnaIntegrationTest {
  private static final int SAFE_ATTRIBUTES = WindowsSharedFolderNativeBridge.OBJ_CASE_INSENSITIVE
      | WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE;

  @TempDir Path temp;

  @Test
  void volumeCapacityOverflowFailsClosed() {
    assertThatThrownBy(() -> JnaWindowsSharedFolderNativeBridge.checkedUsableSpace(
        Long.MAX_VALUE, 2, 4096))
        .isInstanceOf(WindowsSharedFolderNativeBridge.NativeBoundaryException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  void realJnaBridgeListsMetadataAndStreamsThroughNativeHandles() throws Exception {
    Path root = Files.createDirectory(temp.resolve("root"));
    Path file = Files.write(root.resolve("letter.txt"), "abcdef".getBytes(StandardCharsets.UTF_8));
    Instant expectedModified = Files.getLastModifiedTime(file).toInstant();
    JnaWindowsSharedFolderNativeBridge bridge = new JnaWindowsSharedFolderNativeBridge();
    NativeHandle rootHandle = bridge.openRoot(root, SAFE_ATTRIBUTES);
    try {
      var entry = bridge.listDirectory(rootHandle).stream()
          .filter(candidate -> candidate.name().equals("letter.txt"))
          .findFirst().orElseThrow();
      assertThat(entry.size()).isEqualTo(6);
      assertThat(entry.modifiedAt()).isAfter(Instant.EPOCH);
      assertThat(Math.abs(entry.modifiedAt().toEpochMilli() - expectedModified.toEpochMilli()))
          .isLessThanOrEqualTo(2_000);

      NativeHandle fileHandle = bridge.openRelative(
          rootHandle, "letter.txt", WindowsSharedFolderNativeBridge.OpenKind.FILE, SAFE_ATTRIBUTES);
      try {
        assertThat(entry.identity().sameFile(bridge.metadata(fileHandle).identity())).isTrue();
        assertThat(bridge.metadata(fileHandle).size()).isEqualTo(6);
        assertThat(bridge.seek(fileHandle, 2)).isEqualTo(2);
        byte[] bytes = new byte[2];
        assertThat(bridge.read(fileHandle, bytes, 0, bytes.length)).isEqualTo(2);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("cd");
      } finally {
        bridge.close(fileHandle);
      }
    } finally {
      bridge.close(rootHandle);
    }
  }

  @Test
  void realJnaBridgeCreatesWritesFlushesRenamesAndDeletesThroughRelativeHandles() throws Exception {
    Path root = Files.createDirectory(temp.resolve("mutations"));
    JnaWindowsSharedFolderNativeBridge bridge = new JnaWindowsSharedFolderNativeBridge();
    NativeHandle rootHandle = bridge.openRoot(root, SAFE_ATTRIBUTES);
    try {
      NativeHandle folder = bridge.createRelative(
          rootHandle, "folder", WindowsSharedFolderNativeBridge.OpenKind.DIRECTORY, SAFE_ATTRIBUTES);
      try {
        NativeHandle file = bridge.createRelative(
            folder, "upload.bin", WindowsSharedFolderNativeBridge.OpenKind.FILE, SAFE_ATTRIBUTES);
        try {
          assertThat(bridge.write(file, new byte[] {1, 2, 3, 4}, 0, 4)).isEqualTo(4);
          bridge.flush(file);
          assertThat(bridge.metadata(file).size()).isEqualTo(4);
          bridge.truncate(file, 2);
          assertThat(bridge.metadata(file).size()).isEqualTo(2);
          assertThat(bridge.seek(file, 2)).isEqualTo(2);
          assertThat(bridge.write(file, new byte[] {3, 4}, 0, 2)).isEqualTo(2);
          bridge.flush(file);
          bridge.rename(file, rootHandle, "final.bin", false);
        } finally {
          bridge.close(file);
        }
      } finally {
        bridge.close(folder);
      }

      NativeHandle finalFile = bridge.openRelativeForMutation(
          rootHandle, "final.bin", WindowsSharedFolderNativeBridge.OpenKind.FILE, SAFE_ATTRIBUTES);
      try {
        assertThat(bridge.metadata(finalFile).regularFile()).isTrue();
        bridge.delete(finalFile);
      } finally {
        bridge.close(finalFile);
      }
      assertThat(Files.exists(root.resolve("final.bin"))).isFalse();
    } finally {
      bridge.close(rootHandle);
    }
  }

  @Test
  void realDeleteChainBlocksExternalSourceAndAncestorRenameAfterValidation() throws Exception {
    Path root = Files.createDirectory(temp.resolve("delete-chain-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("delete-chain-system"));
    Path outside = Files.createDirectory(temp.resolve("delete-chain-outside"));
    Path ancestor = Files.createDirectories(root.resolve("documents/archive"));
    Path source = Files.writeString(ancestor.resolve("source.txt"), "inside");
    InterceptingBridge bridge = new InterceptingBridge();
    AtomicReference<Throwable> sourceMove = new AtomicReference<>();
    AtomicReference<Throwable> ancestorMove = new AtomicReference<>();
    bridge.beforeDelete = () -> {
      sourceMove.set(catchThrowable(() -> Files.move(source, outside.resolve("source.txt"))));
      ancestorMove.set(catchThrowable(() -> Files.move(
          root.resolve("documents"), outside.resolve("documents"))));
    };
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        root, systemRoot, bridge);
    try {
      var observed = boundary.metadata("documents/archive/source.txt");
      boundary.delete("documents/archive/source.txt", observed);

      assertThat(sourceMove.get()).isInstanceOf(java.io.IOException.class);
      assertThat(ancestorMove.get()).isInstanceOf(java.io.IOException.class);
      assertThat(Files.exists(source)).isFalse();
      assertThat(outside).isEmptyDirectory();
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void realRenameChainBlocksExternalSourceAndAncestorRenameAfterValidation() throws Exception {
    Path root = Files.createDirectory(temp.resolve("rename-chain-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("rename-chain-system"));
    Path outside = Files.createDirectory(temp.resolve("rename-chain-outside"));
    Path ancestor = Files.createDirectories(root.resolve("documents/archive"));
    Path source = Files.writeString(ancestor.resolve("source.txt"), "inside");
    InterceptingBridge bridge = new InterceptingBridge();
    AtomicReference<Throwable> sourceMove = new AtomicReference<>();
    AtomicReference<Throwable> ancestorMove = new AtomicReference<>();
    bridge.beforeRename = () -> {
      sourceMove.set(catchThrowable(() -> Files.move(source, outside.resolve("source.txt"))));
      ancestorMove.set(catchThrowable(() -> Files.move(
          root.resolve("documents"), outside.resolve("documents"))));
    };
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        root, systemRoot, bridge);
    try {
      var observed = boundary.metadata("documents/archive/source.txt");
      boundary.rename("documents/archive/source.txt", "documents/archive", "renamed.txt", false,
          observed);

      assertThat(sourceMove.get()).isInstanceOf(java.io.IOException.class);
      assertThat(ancestorMove.get()).isInstanceOf(java.io.IOException.class);
      assertThat(Files.readString(ancestor.resolve("renamed.txt"))).isEqualTo("inside");
      assertThat(outside).isEmptyDirectory();
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void realMutationLeavesBlockSameSizeWritesAndDirectoryChildCreationAtFinalTransition()
      throws Exception {
    Path root = Files.createDirectory(temp.resolve("write-share-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("write-share-system"));
    Path documents = Files.createDirectory(root.resolve("documents"));
    Path source = Files.writeString(documents.resolve("source.txt"), "source");
    Path target = Files.writeString(documents.resolve("target.txt"), "target");
    InterceptingBridge bridge = new InterceptingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        root, systemRoot, bridge);
    try {
      AtomicReference<Throwable> sourceWrite = new AtomicReference<>();
      bridge.beforeRename = () -> sourceWrite.set(catchThrowable(
          () -> Files.writeString(source, "racer!")));
      boundary.rename("documents/source.txt", "documents", "renamed.txt", false,
          boundary.metadata("documents/source.txt"));
      assertThat(sourceWrite.get()).isInstanceOf(java.io.IOException.class);
      assertThat(Files.readString(documents.resolve("renamed.txt"))).isEqualTo("source");

      AtomicReference<Throwable> targetWrite = new AtomicReference<>();
      bridge.beforeRename = () -> targetWrite.set(catchThrowable(
          () -> Files.writeString(target, "racer!")));
      String targetKey = "66666666-6666-6666-6666-666666666666";
      NativeFileMetadata targetMetadata = boundary.metadata("documents/target.txt");
      boundary.quarantineVisible("documents/target.txt", targetKey, targetMetadata);
      assertThat(targetWrite.get()).isInstanceOf(java.io.IOException.class);
      bridge.beforeRename = () -> {};
      boundary.restoreQuarantine(targetKey, "documents", "target.txt", targetMetadata);
      assertThat(Files.readString(target)).isEqualTo("target");

      Path targetDirectory = Files.createDirectory(documents.resolve("empty-target"));
      AtomicReference<Throwable> childCreate = new AtomicReference<>();
      java.util.concurrent.atomic.AtomicBoolean attemptChildCreate =
          new java.util.concurrent.atomic.AtomicBoolean(true);
      AtomicReference<java.util.List<String>> quarantinedPaths = new AtomicReference<>();
      bridge.beforeRename = () -> {
        if (attemptChildCreate.compareAndSet(true, false)) {
          childCreate.set(catchThrowable(
              () -> Files.writeString(targetDirectory.resolve("racer.txt"), "outside")));
        }
      };
      bridge.afterRename = () -> quarantinedPaths.compareAndSet(null, catchPaths(systemRoot));
      String directoryKey = "77777777-7777-7777-7777-777777777777";
      NativeFileMetadata directoryMetadata = boundary.metadata("documents/empty-target");
      Throwable directoryRace = catchThrowable(() -> boundary.quarantineVisible(
          "documents/empty-target", directoryKey, directoryMetadata));
      assertThat(directoryRace).isInstanceOf(NativeBoundaryException.class)
          .hasMessageContaining("non-empty");
      assertThat(directoryRace.getSuppressed()).isEmpty();
      assertThat(childCreate.get()).isNull();
      assertThat(quarantinedPaths.get()).as("paths immediately after quarantine")
          .anyMatch(path -> path.endsWith("racer.txt"));
      assertThat(targetDirectory).exists();
      java.util.List<String> remaining = Files.walk(root)
          .map(path -> root.relativize(path).toString()).toList();
      assertThat(remaining).as("remaining visible paths: %s", remaining)
          .contains("documents\\empty-target\\racer.txt");
      assertThat(Files.readString(targetDirectory.resolve("racer.txt"))).isEqualTo("outside");
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void realUploadFinalizationBlocksSameSizeStagingWriteAtFinalTransition() throws Exception {
    Path root = Files.createDirectory(temp.resolve("upload-write-share-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("upload-write-share-system"));
    InterceptingBridge bridge = new InterceptingBridge();
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        root, systemRoot, bridge);
    String key = "88888888-8888-8888-8888-888888888888";
    try {
      try (var staging = boundary.createStaging(key)) {
        byte[] content = "staged".getBytes(StandardCharsets.UTF_8);
        staging.write(content, 0, content.length);
        staging.flush();
      }
      Path stagedPath = systemRoot.resolve("shared-folder-upload-staging").resolve(key);
      AtomicReference<Throwable> stagingWrite = new AtomicReference<>();
      bridge.beforeRename = () -> stagingWrite.set(catchThrowable(
          () -> Files.writeString(stagedPath, "racer!")));

      boundary.finalizeStaging(key, "", "uploaded.bin", false);

      assertThat(stagingWrite.get()).isInstanceOf(java.io.IOException.class);
      assertThat(Files.readString(root.resolve("uploaded.bin"))).isEqualTo("staged");
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void heldExclusiveStagingHandleDeniesExternalWritesWithoutBlockingUnrelatedQuarantine()
      throws Exception {
    Path root = Files.createDirectory(temp.resolve("independent-visible-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("independent-system-root"));
    Files.writeString(root.resolve("target.bin"), "target");
    WindowsSharedFolderMutationBoundary boundary = WindowsSharedFolderMutationBoundary.forTest(
        root, systemRoot, new JnaWindowsSharedFolderNativeBridge());
    String stagingKey = "11111111-1111-1111-1111-111111111111";
    String quarantineKey = "22222222-2222-2222-2222-222222222222";
    try {
      boundary.createStaging(stagingKey).close();
      Path stagingPath = systemRoot.resolve("shared-folder-upload-staging").resolve(stagingKey);
      NativeFileMetadata target = boundary.metadata("target.bin");
      try (var ignored = boundary.staging(stagingKey)) {
        assertThat(catchThrowable(() -> Files.writeString(stagingPath, "racer")))
            .isInstanceOf(java.io.IOException.class);
        NativeFileMetadata quarantined =
            boundary.quarantineVisible("target.bin", quarantineKey, target);
        assertThat(quarantined.identity().sameFile(target.identity())).isTrue();
      }
      boundary.restoreQuarantine(quarantineKey, "", "target.bin", target);
      assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("target");
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void realBoundaryReopensStagingForAppendThenFinalizesAndDeletesByHandle() throws Exception {
    Path root = Files.createDirectory(temp.resolve("visible-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("system-root"));
    SharedFolderProperties properties = new SharedFolderProperties(
        root, systemRoot, DataSize.ofGigabytes(1), DataSize.ofMegabytes(8), DataSize.ofBytes(1),
        DataSize.ofMegabytes(10), Duration.ofDays(1), Duration.ofDays(1), true);
    WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
    boundary.initialize();
    String key = "33333333-3333-3333-3333-333333333333";
    try {
      assertThat(boundary.usableSystemBytes()).isPositive();
      boundary.createStaging(key).close();
      try (var reopened = boundary.staging(key)) {
        assertThat(reopened.seek(0)).isZero();
        assertThat(reopened.write(new byte[] {5, 6, 7}, 0, 3)).isEqualTo(3);
        reopened.flush();
      }

      var metadata = boundary.finalizeStaging(key, "", "visible.bin", false);
      assertThat(metadata.size()).isEqualTo(3);
      assertThat(Files.readAllBytes(root.resolve("visible.bin"))).containsExactly(5, 6, 7);
      boundary.restoreFinalized("visible.bin", key, metadata);
      assertThat(Files.exists(root.resolve("visible.bin"))).isFalse();
      metadata = boundary.finalizeStaging(key, "", "visible.bin", false);
      assertThat(metadata.size()).isEqualTo(3);
      boundary.delete("visible.bin");
      assertThat(Files.exists(root.resolve("visible.bin"))).isFalse();
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void realBoundaryCopiesBetweenTwoExclusiveStagingHandles() throws Exception {
    Path root = Files.createDirectory(temp.resolve("copy-visible-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("copy-system-root"));
    SharedFolderProperties properties = new SharedFolderProperties(
        root, systemRoot, DataSize.ofGigabytes(1), DataSize.ofMegabytes(8), DataSize.ofBytes(1),
        DataSize.ofMegabytes(10), Duration.ofDays(1), Duration.ofDays(1), true);
    WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
    boundary.initialize();
    String targetKey = "44444444-4444-4444-4444-444444444444";
    String chunkKey = "55555555-5555-5555-5555-555555555555";
    byte[] expected = "native append chunk".getBytes(StandardCharsets.UTF_8);
    try {
      boundary.createStaging(targetKey).close();
      try (var chunk = boundary.createStaging(chunkKey)) {
        assertThat(chunk.write(expected, 0, expected.length)).isEqualTo(expected.length);
        chunk.flush();
      }

      try (var target = boundary.staging(targetKey);
          var source = boundary.staging(chunkKey)) {
        assertThat(target.seek(0)).isZero();
        assertThat(source.seek(0)).isZero();
        byte[] buffer = new byte[64];
        int read = source.read(buffer, 0, buffer.length);
        assertThat(read).isEqualTo(expected.length);
        assertThat(target.write(buffer, 0, read)).isEqualTo(read);
        target.flush();
      }

      assertThat(Files.readAllBytes(
          systemRoot.resolve("shared-folder-upload-staging").resolve(targetKey)))
          .containsExactly(expected);
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void junctionRaceIsRejectedWhenExplicitNativeIntegrationIsEnabled() throws Exception {
    Assumptions.assumeTrue(junctionTestEnabled(),
        "set SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST=true on a capable Windows host");
    Path root = Files.createDirectory(temp.resolve("root"));
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "outside");
    Path junction = root.resolve("swap");
    Process process = new ProcessBuilder(
        "cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    Assumptions.assumeTrue(process.waitFor() == 0, "junction capability unavailable: " + output);

    JnaWindowsSharedFolderNativeBridge bridge = new JnaWindowsSharedFolderNativeBridge();
    WindowsSharedFolderReadBoundary boundary = WindowsSharedFolderReadBoundary.forTest(root, bridge);
    try {
      assertThatThrownBy(() -> boundary.list("swap"))
          .isInstanceOf(UnsafeSharedPathException.class)
          .hasMessage("Shared-folder item is not available");
    } finally {
      boundary.destroy();
      Files.deleteIfExists(junction);
    }
  }

  @Test
  void junctionMutationIsRejectedWhenExplicitNativeIntegrationIsEnabled() throws Exception {
    Assumptions.assumeTrue(junctionTestEnabled(),
        "set SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST=true on a capable Windows host");
    Path root = Files.createDirectory(temp.resolve("mutation-junction-root"));
    Path systemRoot = Files.createDirectory(temp.resolve("mutation-junction-system"));
    Path outside = Files.createDirectory(temp.resolve("mutation-junction-outside"));
    Path junction = root.resolve("swap");
    Process process = new ProcessBuilder(
        "cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    Assumptions.assumeTrue(process.waitFor() == 0, "junction capability unavailable: " + output);
    SharedFolderProperties properties = new SharedFolderProperties(
        root, systemRoot, DataSize.ofGigabytes(1), DataSize.ofMegabytes(8), DataSize.ofBytes(1),
        DataSize.ofMegabytes(10), Duration.ofDays(1), Duration.ofDays(1), true);
    WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
    boundary.initialize();
    try {
      assertThatThrownBy(() -> boundary.createDirectory("swap", "escaped"))
          .isInstanceOf(WindowsSharedFolderNativeBridge.NativeBoundaryException.class);
      assertThat(Files.exists(outside.resolve("escaped"))).isFalse();
    } finally {
      boundary.destroy();
      Files.deleteIfExists(junction);
    }
  }

  private static boolean junctionTestEnabled() {
    return Boolean.getBoolean("sharedFolder.runWindowsNativeJunctionTest")
        || Boolean.parseBoolean(System.getenv("SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST"));
  }

  private static final class InterceptingBridge implements WindowsSharedFolderNativeBridge {
    private final JnaWindowsSharedFolderNativeBridge delegate =
        new JnaWindowsSharedFolderNativeBridge();
    private Runnable beforeDelete = () -> {};
    private Runnable beforeRename = () -> {};
    private Runnable afterRename = () -> {};

    @Override public NativeHandle openRoot(Path path, int flags) {
      return delegate.openRoot(path, flags);
    }
    @Override public NativeHandle openRootForMutation(Path path, int flags) {
      return delegate.openRootForMutation(path, flags);
    }
    @Override public NativeHandle openRelative(NativeHandle parent, String name, OpenKind kind, int flags) {
      return delegate.openRelative(parent, name, kind, flags);
    }
    @Override public NativeHandle openRelativePinned(
        NativeHandle parent, String name, OpenKind kind, int flags) {
      return delegate.openRelativePinned(parent, name, kind, flags);
    }
    @Override public NativeHandle openRelativeForMutation(
        NativeHandle parent, String name, OpenKind kind, int flags) {
      return delegate.openRelativeForMutation(parent, name, kind, flags);
    }
    @Override public NativeHandle openRelativeForExclusiveMutation(
        NativeHandle parent, String name, OpenKind kind, int flags) {
      return delegate.openRelativeForExclusiveMutation(parent, name, kind, flags);
    }
    @Override public NativeHandle createRelative(
        NativeHandle parent, String name, OpenKind kind, int flags) {
      return delegate.createRelative(parent, name, kind, flags);
    }
    @Override public NativeFileMetadata metadata(NativeHandle handle) {
      return delegate.metadata(handle);
    }
    @Override public List<DirectoryEntry> listDirectory(NativeHandle directory) {
      return delegate.listDirectory(directory);
    }
    @Override public int read(NativeHandle handle, byte[] buffer, int offset, int length) {
      return delegate.read(handle, buffer, offset, length);
    }
    @Override public long seek(NativeHandle handle, long offset) {
      return delegate.seek(handle, offset);
    }
    @Override public int write(NativeHandle handle, byte[] buffer, int offset, int length) {
      return delegate.write(handle, buffer, offset, length);
    }
    @Override public void flush(NativeHandle handle) { delegate.flush(handle); }
    @Override public void truncate(NativeHandle handle, long size) { delegate.truncate(handle, size); }
    @Override public void rename(
        NativeHandle source, NativeHandle destinationParent, String name, boolean replace) {
      beforeRename.run();
      delegate.rename(source, destinationParent, name, replace);
      afterRename.run();
    }
    @Override public void delete(NativeHandle source) {
      beforeDelete.run();
      delegate.delete(source);
    }
    @Override public long usableSpace(NativeHandle handle) { return delegate.usableSpace(handle); }
    @Override public void close(NativeHandle handle) { delegate.close(handle); }
  }

  private static java.util.List<String> catchPaths(Path root) {
    try (var paths = Files.walk(root)) {
      return paths.map(Path::toString).toList();
    } catch (java.io.IOException exception) {
      throw new AssertionError(exception);
    }
  }
}

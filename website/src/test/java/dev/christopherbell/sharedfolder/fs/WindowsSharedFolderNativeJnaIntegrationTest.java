package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeHandle;
import dev.christopherbell.configuration.SharedFolderProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
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
}

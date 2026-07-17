package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Windows-only checks for JNA structure layout and calling conventions against the real NT APIs. */
@EnabledOnOs(OS.WINDOWS)
class WindowsSharedFolderNativeJnaIntegrationTest {
  private static final int SAFE_ATTRIBUTES = WindowsSharedFolderNativeBridge.OBJ_CASE_INSENSITIVE
      | WindowsSharedFolderNativeBridge.OBJ_DONT_REPARSE;

  @TempDir Path temp;

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

  private static boolean junctionTestEnabled() {
    return Boolean.getBoolean("sharedFolder.runWindowsNativeJunctionTest")
        || Boolean.parseBoolean(System.getenv("SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST"));
  }
}

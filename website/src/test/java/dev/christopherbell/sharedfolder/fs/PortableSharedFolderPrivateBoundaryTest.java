package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortableSharedFolderPrivateBoundaryTest {
  @TempDir Path temp;

  @Test
  void createsAValidatedDirectChildUnderAPrecreatedOrdinarySystemRoot() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);

    Path staging = systemRoot.resolve("shared-folder-upload-staging");
    Files.createDirectory(staging);

    assertThat(Files.isDirectory(staging, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue();
    assertThat(Files.isSymbolicLink(staging)).isFalse();
    assertThat(Files.getFileStore(staging).name()).isEqualTo(Files.getFileStore(systemRoot).name());
    assertThat(Files.getFileStore(staging).type()).isEqualTo(Files.getFileStore(systemRoot).type());
    assertThat(staging.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS)).startsWith(systemRoot);

    Files.delete(staging);
    staging = boundary.directory("shared-folder-upload-staging");

    assertThat(staging).isDirectory();
    assertThat(staging.getParent()).isEqualTo(systemRoot.toRealPath());
    boundary.verify();
  }

  @Test
  void wrapsPrivateLeafOperationsWithIdentityChecks() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);

    boundary.operateOnRegularFile(
        "shared-folder-upload-staging", "session-1",
        PortableSharedFolderPrivateBoundary.FileAccess.CREATE_NEW, channel -> {
      channel.write(ByteBuffer.wrap("payload".getBytes()));
      return null;
    });

    assertThat(Files.readString(systemRoot.resolve("shared-folder-upload-staging/session-1")))
        .isEqualTo("payload");
  }

  @Test
  void rejectsSymlinkPrivateLeavesBeforeChannelOperations() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("linked-system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);
    Path staging = boundary.directory("shared-folder-upload-staging");
    Path outside = Files.writeString(temp.resolve("outside-file"), "outside");
    AtomicBoolean invoked = new AtomicBoolean();
    Path symlink = staging.resolve("symlink-leaf");
    try {
      Files.createSymbolicLink(symlink, outside);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
          "shared-folder-upload-staging", "symlink-leaf",
          PortableSharedFolderPrivateBoundary.FileAccess.WRITE, channel -> {
            invoked.set(true);
            channel.write(ByteBuffer.wrap("changed".getBytes()));
            return null;
          })).isInstanceOf(
              PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);
      assertThat(invoked).isFalse();
      assertThat(Files.readString(outside)).isEqualTo("outside");
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false,
          "symbolic-link capability unavailable: " + exception.getMessage());
    }

  }

  @Test
  void rejectsHardlinkPrivateLeavesBeforeChannelOperations() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("hardlinked-system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);
    Path staging = boundary.directory("shared-folder-upload-staging");
    Path outside = Files.writeString(temp.resolve("hardlink-outside-file"), "outside");
    AtomicBoolean invoked = new AtomicBoolean();
    Path hardlink = staging.resolve("hardlink-leaf");
    try {
      Files.createLink(hardlink, outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false,
          "hard-link capability unavailable: " + exception.getMessage());
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
        "shared-folder-upload-staging", "hardlink-leaf",
        PortableSharedFolderPrivateBoundary.FileAccess.WRITE, channel -> {
          invoked.set(true);
          channel.write(ByteBuffer.wrap("changed".getBytes()));
          return null;
        })).isInstanceOf(
            PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);
    assertThat(invoked).isFalse();
    assertThat(Files.readString(outside)).isEqualTo("outside");
  }

  @Test
  void retainedChannelCannotBeRedirectedByMidOperationLeafSubstitution() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("leaf-swap-system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);
    Path staging = boundary.directory("shared-folder-upload-staging");
    Path leaf = Files.writeString(staging.resolve("session-1"), "private");
    Path displaced = staging.resolve("displaced");
    Path outside = Files.writeString(temp.resolve("leaf-swap-outside"), "outside");

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
        "shared-folder-upload-staging", "session-1",
        PortableSharedFolderPrivateBoundary.FileAccess.WRITE, channel -> {
          Files.move(leaf, displaced);
          Files.createLink(leaf, outside);
          channel.position(0);
          channel.write(ByteBuffer.wrap("changed".getBytes()));
          channel.truncate("changed".length());
          return null;
        })).isInstanceOf(
            PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);

    assertThat(Files.readString(outside)).isEqualTo("outside");
    assertThat(Files.readString(displaced)).isEqualTo("changed");
  }

  @Test
  void rejectsPrivateDirectorySubstitutionBeforeTheLeafOperationRuns() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);
    Path staging = boundary.directory("shared-folder-upload-staging");
    Files.move(staging, systemRoot.resolve("displaced-staging"));
    Files.createDirectory(staging);
    AtomicBoolean invoked = new AtomicBoolean();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
        "shared-folder-upload-staging", "session-1",
        PortableSharedFolderPrivateBoundary.FileAccess.CREATE_NEW, channel -> {
          invoked.set(true);
          channel.write(ByteBuffer.wrap("outside-write".getBytes()));
          return null;
        })).isInstanceOf(
            PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);

    assertThat(invoked).isFalse();
    assertThat(Files.notExists(staging.resolve("session-1"))).isTrue();
    assertThat(Files.notExists(systemRoot.resolve("displaced-staging/session-1"))).isTrue();
  }

  @Test
  void rejectsSystemRootSubstitutionBeforeTheLeafOperationRuns() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);
    boundary.directory("shared-folder-upload-staging");
    Path displaced = temp.resolve("displaced-system");
    Files.move(systemRoot, displaced);
    Files.createDirectory(systemRoot);
    AtomicBoolean invoked = new AtomicBoolean();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
        "shared-folder-upload-staging", "session-1",
        PortableSharedFolderPrivateBoundary.FileAccess.CREATE_NEW, channel -> {
          invoked.set(true);
          return null;
        })).isInstanceOf(
            PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);

    assertThat(invoked).isFalse();
    assertThat(Files.notExists(systemRoot.resolve("shared-folder-upload-staging/session-1")))
        .isTrue();
    assertThat(Files.notExists(displaced.resolve("shared-folder-upload-staging/session-1")))
        .isTrue();
  }

  @Test
  void rejectsAncestorSubstitutionBeforeTheLeafOperationRuns() throws Exception {
    Path ancestor = Files.createDirectory(temp.resolve("ancestor"));
    Path systemRoot = Files.createDirectory(ancestor.resolve("system"));
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot);
    boundary.directory("shared-folder-upload-staging");
    Path displaced = temp.resolve("displaced-ancestor");
    Files.move(ancestor, displaced);
    Files.createDirectory(ancestor);
    Files.createDirectory(systemRoot);
    AtomicBoolean invoked = new AtomicBoolean();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
        "shared-folder-upload-staging", "session-1",
        PortableSharedFolderPrivateBoundary.FileAccess.CREATE_NEW, channel -> {
          invoked.set(true);
          channel.write(ByteBuffer.wrap("outside-write".getBytes()));
          return null;
        })).isInstanceOf(
            PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);

    assertThat(invoked).isFalse();
    assertThat(Files.notExists(systemRoot.resolve("shared-folder-upload-staging/session-1")))
        .isTrue();
    assertThat(Files.notExists(
        displaced.resolve("system/shared-folder-upload-staging/session-1"))).isTrue();
  }

  @Test
  void rejectsAMountedPrivateChildBeforeTheLeafOperationRuns() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("system"));
    var facts = new NioSharedFolderFileSystemBoundary(canonicalPath -> false) {
      @Override
      public boolean isMountPoint(Path path) {
        return path.getFileName() != null
            && path.getFileName().toString().equals("shared-folder-upload-staging");
      }
    };
    PortableSharedFolderPrivateBoundary boundary =
        new PortableSharedFolderPrivateBoundary(systemRoot, facts);
    AtomicBoolean invoked = new AtomicBoolean();

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
        "shared-folder-upload-staging", "session-1",
        PortableSharedFolderPrivateBoundary.FileAccess.CREATE_NEW, channel -> {
          invoked.set(true);
          return null;
        })).isInstanceOf(
            PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);

    assertThat(invoked).isFalse();
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void rejectsAPrivateDirectoryJunctionBeforeTheLeafOperationRuns() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Boolean.getBoolean("sharedFolder.runWindowsNativeJunctionTest")
            || Boolean.parseBoolean(
                System.getenv("SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST")),
        "set SHARED_FOLDER_RUN_WINDOWS_NATIVE_JUNCTION_TEST=true on a capable Windows host");
    Path systemRoot = Files.createDirectory(temp.resolve("junction-system"));
    Path outside = Files.createDirectory(temp.resolve("junction-outside"));
    Path junction = systemRoot.resolve("shared-folder-upload-staging");
    Process process = new ProcessBuilder(
        "cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes());
    org.junit.jupiter.api.Assumptions.assumeTrue(
        process.waitFor() == 0, "junction capability unavailable: " + output);
    AtomicBoolean invoked = new AtomicBoolean();

    try {
      PortableSharedFolderPrivateBoundary boundary =
          new PortableSharedFolderPrivateBoundary(systemRoot);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
          "shared-folder-upload-staging", "session-1",
          PortableSharedFolderPrivateBoundary.FileAccess.CREATE_NEW, channel -> {
            invoked.set(true);
            channel.write(ByteBuffer.wrap("outside-write".getBytes()));
            return null;
          })).isInstanceOf(
              PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);

      assertThat(invoked).isFalse();
      assertThat(Files.notExists(outside.resolve("session-1"))).isTrue();
    } finally {
      Files.deleteIfExists(junction);
    }
  }

  @Test
  void rejectsUnsupportedPrivateFilesystemProviders() throws Exception {
    Path archive = temp.resolve("private-provider.zip");
    try (var zip = java.nio.file.FileSystems.newFileSystem(
        java.net.URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
      Path systemRoot = Files.createDirectory(zip.getPath("/system"));
      var facts = new NioSharedFolderFileSystemBoundary(canonicalPath -> false) {
        @Override public boolean isMountPoint(Path path) { return false; }
      };
      PortableSharedFolderPrivateBoundary boundary =
          new PortableSharedFolderPrivateBoundary(systemRoot, facts);
      AtomicBoolean invoked = new AtomicBoolean();

      org.assertj.core.api.Assertions.assertThatThrownBy(() -> boundary.operateOnRegularFile(
          "shared-folder-upload-staging", "session-1",
          PortableSharedFolderPrivateBoundary.FileAccess.CREATE_NEW, channel -> {
            invoked.set(true);
            return null;
          })).isInstanceOf(
              PortableSharedFolderPrivateBoundary.BoundaryUnavailableException.class);

      assertThat(invoked).isFalse();
    }
  }
}

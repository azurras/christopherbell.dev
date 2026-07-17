package dev.christopherbell.sharedfolder.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioSharedFolderFileSystemBoundaryTest {
  @TempDir Path temp;

  @Test
  void defaultBoundaryProvesOrdinaryDescendantIdentityAndFileStore() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path child = Files.createDirectory(root.resolve("music"));
    var boundary = new NioSharedFolderFileSystemBoundary();

    assertThat(boundary.absoluteNormalized(root.resolve(".").resolve("music").resolve("..")))
        .isEqualTo(root.toAbsolutePath().normalize());
    assertThat(boundary.realPath(root)).isEqualTo(boundary.realPathNoFollow(root));
    assertThat(boundary.realPath(child)).startsWith(boundary.realPath(root));
    assertThat(boundary.sameFileStore(root, child)).isTrue();
    assertThat(boundary.isMountPoint(child)).isFalse();
  }

  @Test
  void failsClosedWhenMountMetadataCannotBeRead() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path child = Files.createDirectory(root.resolve("music"));
    var boundary = new NioSharedFolderFileSystemBoundary(
        canonicalPath -> {
          throw new IOException("mount metadata is unreadable");
        });

    assertThatThrownBy(() -> boundary.isMountPoint(child))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unreadable");
  }

  @Test
  void failsClosedWhenLinuxMountMetadataIsUnavailable() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path child = Files.createDirectory(root.resolve("music"));
    var boundary = new NioSharedFolderFileSystemBoundary(
        new LinuxSharedFolderMountMetadata(temp.resolve("missing-mountinfo")));

    assertThatThrownBy(() -> boundary.isMountPoint(child))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void failsClosedWhenLinuxMountMetadataIsMalformed() throws Exception {
    Path root = Files.createDirectory(temp.resolve("shared"));
    Path child = Files.createDirectory(root.resolve("music"));
    Path malformedMountInfo = Files.writeString(temp.resolve("mountinfo"), "not mount metadata");
    var boundary = new NioSharedFolderFileSystemBoundary(
        new LinuxSharedFolderMountMetadata(malformedMountInfo));

    assertThatThrownBy(() -> boundary.isMountPoint(child))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("malformed");
  }
}

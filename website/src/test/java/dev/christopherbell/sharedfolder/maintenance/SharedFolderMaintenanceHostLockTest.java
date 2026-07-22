package dev.christopherbell.sharedfolder.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedFolderMaintenanceHostLockTest {
  @TempDir Path temp;

  @Test
  void fixedLockFileStaysUnderTheNormalizedSystemRootAndSurvivesRelease() throws Exception {
    Path systemRoot = Files.createDirectory(temp.resolve("system-root"));
    Path configuredRoot = systemRoot.resolve("unused").resolve("..");
    SharedFolderMaintenanceHostLock first = new SharedFolderMaintenanceHostLock(configuredRoot);
    SharedFolderMaintenanceHostLock peer = new SharedFolderMaintenanceHostLock(configuredRoot);

    try (SharedFolderMaintenanceHostLock.Handle handle = first.tryAcquire().orElseThrow()) {
      assertThat(peer.tryAcquire()).isEmpty();
    }

    try (var files = Files.list(systemRoot)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .containsExactly("shared-folder-maintenance.lock");
    }
    try (SharedFolderMaintenanceHostLock.Handle ignored = peer.tryAcquire().orElseThrow()) {
      assertThat(Files.exists(systemRoot.resolve("shared-folder-maintenance.lock"))).isTrue();
    }
  }

  @Test
  void unavailableRootFailsClosedWithoutDisclosingItsPath() {
    Path unavailableRoot = temp.resolve("private-unavailable-root");
    SharedFolderMaintenanceHostLock lock = new SharedFolderMaintenanceHostLock(unavailableRoot);

    assertThatThrownBy(lock::tryAcquire)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Shared-folder maintenance host lock is unavailable")
        .message()
        .doesNotContain(unavailableRoot.toString());
  }

  @Test
  void malformedRootFailsClosedWithoutDisclosingAPath() {
    assertThatThrownBy(() -> new SharedFolderMaintenanceHostLock((Path) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Shared-folder maintenance host lock root is invalid");
  }
}

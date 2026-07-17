package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.model.SharedFolderCreateFolderRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderMoveRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderRenameRequest;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.unit.DataSize;

class SharedFolderMutationServiceTest {
  @TempDir Path temp;

  @Test
  void createFolderRequiresFreshWriteAccessAndReturnsOnlyRelativeMetadata() throws Exception {
    Path root = Files.createDirectories(temp.resolve("shared"));
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderMutationService mutations = new SharedFolderMutationService(access, properties(root));

    var entry = mutations.createFolder(new SharedFolderCreateFolderRequest("", "docs"));

    verify(access).requireWrite();
    assertThat(entry.path()).isEqualTo("docs");
    assertThat(entry.observedToken()).isNotBlank();
    assertThat(entry.toString()).doesNotContain(root.toString());
    assertThat(Files.isDirectory(root.resolve("docs"))).isTrue();
  }

  @Test
  void renameMoveAndExplicitReplaceRequireCurrentObservedTokens() throws Exception {
    Path root = Files.createDirectories(temp.resolve("shared"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Path archive = Files.createDirectories(root.resolve("archive"));
    Files.writeString(docs.resolve("a.txt"), "new-content");
    Files.writeString(archive.resolve("a.txt"), "old-content");
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        mock(SharedFolderAccessService.class), properties(root));

    String renameToken = mutations.observedToken("docs/a.txt");
    mutations.rename(new SharedFolderRenameRequest("docs/a.txt", "b.txt", renameToken));

    String sourceToken = mutations.observedToken("docs/b.txt");
    assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
        "docs/b.txt", "archive", "a.txt", sourceToken, false, null)));
    String targetToken = mutations.observedToken("archive/a.txt");
    mutations.move(new SharedFolderMoveRequest(
        "docs/b.txt", "archive", "a.txt", sourceToken, true, targetToken));

    assertThat(Files.readString(archive.resolve("a.txt"))).isEqualTo("new-content");
    assertThat(Files.exists(docs.resolve("b.txt"))).isFalse();
  }

  @Test
  void staleTokensCaseOnlyRenameAndPhysicalDeleteHaveConflictSafeSemantics() throws Exception {
    Path root = Files.createDirectories(temp.resolve("shared"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("Case.txt"), "first");
    Files.createDirectories(docs.resolve("non-empty"));
    Files.writeString(docs.resolve("non-empty/child.txt"), "child");
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        mock(SharedFolderAccessService.class), properties(root));

    String stale = mutations.observedToken("docs/Case.txt");
    Files.writeString(docs.resolve("Case.txt"), "changed-after-observation");
    assertConflict(() -> mutations.rename(new SharedFolderRenameRequest("docs/Case.txt", "stale.txt", stale)));

    String caseToken = mutations.observedToken("docs/Case.txt");
    mutations.rename(new SharedFolderRenameRequest("docs/Case.txt", "case.txt", caseToken));
    assertThat(Files.readString(docs.resolve("case.txt"))).isEqualTo("changed-after-observation");

    String nonEmptyToken = mutations.observedToken("docs/non-empty");
    assertConflict(() -> mutations.delete(new SharedFolderDeleteRequest("docs/non-empty", nonEmptyToken)));
    String fileToken = mutations.observedToken("docs/case.txt");
    mutations.delete(new SharedFolderDeleteRequest("docs/case.txt", fileToken));
    assertThat(Files.exists(docs.resolve("case.txt"))).isFalse();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void enabledWindowsMutationsUseNativeHeldHandlesAndRecheckTheObservedFile() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-shared"));
    Files.createDirectories(root.resolve("docs"));
    Files.writeString(root.resolve("docs/original.txt"), "first");
    SharedFolderProperties properties = properties(root);
    Files.createDirectories(properties.systemRoot());
    WindowsSharedFolderMutationBoundary nativeBoundary =
        new WindowsSharedFolderMutationBoundary(properties);
    nativeBoundary.initialize();
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderMutationService mutations =
        new SharedFolderMutationService(access, properties, nativeBoundary);
    try {
      mutations.createFolder(new SharedFolderCreateFolderRequest("docs", "created"));
      String stale = mutations.observedToken("docs/original.txt");
      Files.writeString(root.resolve("docs/original.txt"), "changed");
      assertConflict(() -> mutations.rename(
          new SharedFolderRenameRequest("docs/original.txt", "stale.txt", stale)));

      String current = mutations.observedToken("docs/original.txt");
      var renamed = mutations.rename(
          new SharedFolderRenameRequest("docs/original.txt", "renamed.txt", current));
      var caseRenamed = mutations.rename(new SharedFolderRenameRequest(
          "docs/renamed.txt", "Renamed.txt", mutations.observedToken("docs/renamed.txt")));
      mutations.delete(new SharedFolderDeleteRequest(
          "docs/Renamed.txt", mutations.observedToken("docs/Renamed.txt")));

      assertThat(renamed.path()).isEqualTo("docs/renamed.txt");
      assertThat(caseRenamed.path()).isEqualTo("docs/Renamed.txt");
      assertThat(renamed.observedToken()).isNotBlank();
      assertThat(Files.isDirectory(root.resolve("docs/created"))).isTrue();
      assertThat(Files.exists(root.resolve("docs/Renamed.txt"))).isFalse();
      verify(access, org.mockito.Mockito.times(5)).requireWrite();
    } finally {
      nativeBoundary.destroy();
    }
  }

  @Test
  void targetCreatedAtTheAtomicMoveRaceIsAConflictAndPreservesTheSource() throws Exception {
    Path root = Files.createDirectories(temp.resolve("race-shared"));
    Files.createDirectories(root.resolve("docs"));
    Files.writeString(root.resolve("docs/source.txt"), "source");
    SharedFolderProperties properties = properties(root);
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        mock(SharedFolderAccessService.class), properties) {
      @Override
      protected void moveAtomically(Path source, Path target, boolean replace) throws java.io.IOException {
        Files.writeString(target, "racer");
        super.moveAtomically(source, target, replace);
      }
    };

    String observed = mutations.observedToken("docs/source.txt");
    assertConflict(() -> mutations.rename(
        new SharedFolderRenameRequest("docs/source.txt", "target.txt", observed)));

    assertThat(Files.readString(root.resolve("docs/source.txt"))).isEqualTo("source");
    assertThat(Files.readString(root.resolve("docs/target.txt"))).isEqualTo("racer");
  }

  private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    org.assertj.core.api.Assertions.assertThatThrownBy(action)
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
  }

  private SharedFolderProperties properties(Path root) {
    return new SharedFolderProperties(
        root,
        temp.resolve("system"),
        DataSize.ofGigabytes(10),
        DataSize.ofMegabytes(8),
        DataSize.ofBytes(1),
        DataSize.ofGigabytes(1),
        Duration.ofDays(30),
        Duration.ofDays(180),
        true);
  }
}

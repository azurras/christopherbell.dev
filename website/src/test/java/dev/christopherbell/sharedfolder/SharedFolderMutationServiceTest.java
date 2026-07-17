package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.model.SharedFolderCreateFolderRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderMoveRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderRenameRequest;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

  @Test
  void directServiceValidationRejectsRootMutationAndObservationRequiresFreshReadAccess()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("validation-shared"));
    Files.writeString(root.resolve("visible.txt"), "visible");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderMutationService mutations = new SharedFolderMutationService(access, properties(root));

    assertStatus(400, () -> mutations.delete(new SharedFolderDeleteRequest("", "token")));
    assertStatus(400, () -> mutations.rename(
        new SharedFolderRenameRequest("visible.txt", "", "token")));
    assertStatus(400, () -> mutations.move(new SharedFolderMoveRequest(
        "visible.txt", "../outside", "moved.txt", "token", false, null)));
    mutations.observedToken("visible.txt");

    verify(access, org.mockito.Mockito.times(3)).requireWrite();
    verify(access).requireRead();
    assertThat(Files.readString(root.resolve("visible.txt"))).isEqualTo("visible");
  }

  @Test
  void explicitReplacementQuarantinesObservedTargetAndPreservesARacerOnFinalCreation() throws Exception {
    Path root = Files.createDirectories(temp.resolve("replace-race-shared"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Path system = Files.createDirectories(temp.resolve("system"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.writeString(docs.resolve("target.txt"), "observed-target");
    SharedFolderProperties properties = properties(root);
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        mock(SharedFolderAccessService.class), properties) {
      @Override
      protected void moveAtomically(Path source, Path target, boolean replace) throws java.io.IOException {
        if (source.getFileName().toString().equals("source.txt")
            && target.getFileName().toString().equals("target.txt")) {
          Files.writeString(target, "racer");
        }
        super.moveAtomically(source, target, replace);
      }
    };

    String sourceToken = mutations.observedToken("docs/source.txt");
    String targetToken = mutations.observedToken("docs/target.txt");
    assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
        "docs/source.txt", "docs", "target.txt", sourceToken, true, targetToken)));

    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("racer");
    assertThat(Files.walk(system)
        .filter(Files::isRegularFile)
        .anyMatch(path -> {
          try {
            return Files.readString(path).equals("observed-target");
          } catch (java.io.IOException exception) {
            return false;
          }
        })).isTrue();
  }

  @Test
  void recreatedServiceRestoresObservedTargetAfterCrashImmediatelyFollowingQuarantine() throws Exception {
    Path root = Files.createDirectories(temp.resolve("replace-crash-shared"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.writeString(docs.resolve("target.txt"), "observed-target");
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationRecoveryRepository recoveries = recoveryRepository(records);
    AtomicBoolean crash = new AtomicBoolean(true);
    SharedFolderMutationService crashing = new SharedFolderMutationService(
        access, properties, WindowsSharedFolderMutationBoundary.inactive(), recoveries) {
      @Override
      protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
        if (state == SharedFolderMutationRecoveryState.TARGET_QUARANTINED
            && crash.compareAndSet(true, false)) {
          throw new AssertionError("simulated process death after quarantine");
        }
      }
    };

    String sourceToken = crashing.observedToken("docs/source.txt");
    String targetToken = crashing.observedToken("docs/target.txt");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashing.move(
        new SharedFolderMoveRequest(
            "docs/source.txt", "docs", "target.txt", sourceToken, true, targetToken)))
        .isInstanceOf(AssertionError.class);

    SharedFolderMutationService recreated = new SharedFolderMutationService(
        access, properties, WindowsSharedFolderMutationBoundary.inactive(), recoveries);
    recreated.createFolder(new SharedFolderCreateFolderRequest("docs", "after-recovery"));

    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("observed-target");
    assertThat(records).isEmpty();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void nativeJournalRecoversPostQuarantineAndPostMoveWhenQuarantineIsAlreadyGone() throws Exception {
    for (SharedFolderMutationRecoveryState crashPhase : java.util.List.of(
        SharedFolderMutationRecoveryState.PREPARED,
        SharedFolderMutationRecoveryState.TARGET_QUARANTINED,
        SharedFolderMutationRecoveryState.SOURCE_MOVED)) {
      Path root = Files.createDirectories(temp.resolve("native-journal-" + crashPhase));
      Path docs = Files.createDirectories(root.resolve("docs"));
      Files.writeString(docs.resolve("source.txt"), "source");
      Files.writeString(docs.resolve("target.txt"), "observed-target");
      SharedFolderProperties properties = properties(root);
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
      SharedFolderMutationRecoveryRepository recoveries = recoveryRepository(records);
      Files.createDirectories(properties.systemRoot());
      WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
      boundary.initialize();
      try {
        AtomicBoolean crash = new AtomicBoolean(true);
        SharedFolderMutationService crashing = new SharedFolderMutationService(
            access, properties, boundary, recoveries) {
          @Override
          protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
            if (state == crashPhase && crash.compareAndSet(true, false)) {
              throw new AssertionError("simulated native process death at " + state);
            }
          }
        };
        String sourceToken = crashing.observedToken("docs/source.txt");
        String targetToken = crashing.observedToken("docs/target.txt");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashing.move(
            new SharedFolderMoveRequest(
                "docs/source.txt", "docs", "target.txt", sourceToken, true, targetToken)))
            .isInstanceOf(AssertionError.class);

        if (crashPhase == SharedFolderMutationRecoveryState.SOURCE_MOVED) {
          SharedFolderMutationRecovery record = records.values().iterator().next();
          var quarantined = boundary.quarantineMetadata(record.getQuarantineKey());
          boundary.deleteQuarantine(record.getQuarantineKey(), quarantined);
        }

        SharedFolderMutationService recreated = new SharedFolderMutationService(
            access, properties, boundary, recoveries);
        recreated.createFolder(new SharedFolderCreateFolderRequest("docs", "after-recovery"));

        if (crashPhase == SharedFolderMutationRecoveryState.PREPARED
            || crashPhase == SharedFolderMutationRecoveryState.TARGET_QUARANTINED) {
          assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
          assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("observed-target");
        } else {
          assertThat(Files.exists(docs.resolve("source.txt"))).isFalse();
          assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("source");
        }
        assertThat(records).isEmpty();
      } finally {
        boundary.destroy();
      }
    }
  }

  @Test
  void nativeStatusTranslationDistinguishesMissingConflictAndUnavailable() {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
    when(boundary.nativeMode()).thenReturn(true);
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        access, properties(temp.resolve("native-status-root")), boundary);

    for (var mapping : java.util.Map.of(
        0xC0000034, 404,
        0xC0000035, 409,
        0xC0000043, 409,
        0xC0000001, 503).entrySet()) {
      org.mockito.Mockito.doThrow(
          new dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException(
              "native failure", mapping.getKey()))
          .when(boundary).metadata("missing.txt");
      assertStatus(mapping.getValue(), () -> mutations.observedToken("missing.txt"));
    }
  }

  @Test
  void portableStatusTranslationDistinguishesMissingChildFromUnavailableRoot() throws Exception {
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    Path root = Files.createDirectories(temp.resolve("portable-status-root"));
    SharedFolderMutationService available = new SharedFolderMutationService(access, properties(root));
    assertStatus(404, () -> available.observedToken("missing.txt"));

    SharedFolderMutationService unavailable = new SharedFolderMutationService(
        access, properties(temp.resolve("absent-root")));
    assertStatus(503, () -> unavailable.observedToken("missing.txt"));
  }

  @SuppressWarnings("unchecked")
  private SharedFolderMutationRecoveryRepository recoveryRepository(
      Map<String, SharedFolderMutationRecovery> records) {
    SharedFolderMutationRecoveryRepository repository =
        mock(SharedFolderMutationRecoveryRepository.class);
    when(repository.save(any(SharedFolderMutationRecovery.class))).thenAnswer(invocation -> {
      SharedFolderMutationRecovery recovery = invocation.getArgument(0);
      records.put(recovery.getId(), recovery.copy());
      return recovery.copy();
    });
    when(repository.findTop100ByOwnerIdOrderByUpdatedAtAsc(any(String.class))).thenAnswer(
        invocation -> records.values().stream()
            .filter(record -> record.getOwnerId().equals(invocation.getArgument(0)))
            .map(SharedFolderMutationRecovery::copy).toList());
    org.mockito.Mockito.doAnswer(invocation -> {
      records.remove(invocation.getArgument(0));
      return null;
    }).when(repository).deleteById(any(String.class));
    return repository;
  }

  private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    org.assertj.core.api.Assertions.assertThatThrownBy(action)
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
  }

  private void assertStatus(
      int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    org.assertj.core.api.Assertions.assertThatThrownBy(action)
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(expected));
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

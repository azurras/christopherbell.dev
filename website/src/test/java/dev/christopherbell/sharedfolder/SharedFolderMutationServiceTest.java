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
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryRepository;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationRecoveryState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    AtomicBoolean injectedRacer = new AtomicBoolean();
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        mock(SharedFolderAccessService.class), properties) {
      @Override
      protected void moveAtomically(Path source, Path target, boolean replace) throws java.io.IOException {
        if (source.getFileName().toString().equals("source.txt")
            && target.getFileName().toString().equals("target.txt")
            && injectedRacer.compareAndSet(false, true)) {
          Files.writeString(target, "racer");
          throw new java.nio.file.FileAlreadyExistsException(target.toString());
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
    assertThat(injectedRacer).isTrue();
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

    records.values().forEach(record -> record.setOperationLeaseExpiresAt(Instant.now().minusSeconds(1)));

    SharedFolderMutationService recreated = new SharedFolderMutationService(
        access, properties, WindowsSharedFolderMutationBoundary.inactive(), recoveries);
    recreated.createFolder(new SharedFolderCreateFolderRequest("docs", "after-recovery"));

    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("observed-target");
    assertThat(records).isEmpty();
  }

  @Test
  void liveMutationLeasePreventsConcurrentRecoveryAtPreparedAndPhysicalQuarantine() throws Exception {
    for (SharedFolderMutationRecoveryState blockedPhase : java.util.List.of(
        SharedFolderMutationRecoveryState.PREPARED,
        SharedFolderMutationRecoveryState.TARGET_QUARANTINED)) {
      Path root = Files.createDirectories(temp.resolve("live-mutation-lease-" + blockedPhase));
      Path docs = Files.createDirectories(root.resolve("docs"));
      Files.writeString(docs.resolve("source.txt"), "source");
      Files.writeString(docs.resolve("target.txt"), "target");
      SharedFolderProperties properties = properties(root);
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
      SharedFolderMutationRecoveryRepository repository = recoveryRepository(records);
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      SharedFolderMutationService writer = new SharedFolderMutationService(
          access, properties, WindowsSharedFolderMutationBoundary.inactive(), repository) {
        @Override
        protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
          if (state == blockedPhase) {
            entered.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new AssertionError(exception);
            }
          }
        }
      };
      String sourceToken = writer.observedToken("docs/source.txt");
      String targetToken = writer.observedToken("docs/target.txt");
      try (var executor = Executors.newSingleThreadExecutor()) {
        var active = executor.submit(() -> writer.move(new SharedFolderMoveRequest(
            "docs/source.txt", "docs", "target.txt", sourceToken, true, targetToken)));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        SharedFolderMutationRecovery live = records.values().iterator().next();
        assertThat(live.getOperationLeaseToken()).isNotBlank();
        assertThat(live.getOperationLeaseExpiresAt()).isAfter(Instant.now());

        new SharedFolderMutationService(
            access, properties, WindowsSharedFolderMutationBoundary.inactive(), repository)
            .reconcileStartup();

        assertThat(records).containsKey(live.getId());
        assertThat(records.get(live.getId()).getOperationLeaseToken())
            .isEqualTo(live.getOperationLeaseToken());
        if (blockedPhase == SharedFolderMutationRecoveryState.PREPARED) {
          assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("target");
        } else {
          assertThat(Files.notExists(docs.resolve("target.txt"))).isTrue();
        }
        release.countDown();
        active.get(10, TimeUnit.SECONDS);
      }
      assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("source");
      assertThat(records).isEmpty();
    }
  }

  @Test
  void renewedMutationLeaseCannotBeStolenByAStaleExpiredRecoveryClaim() throws Exception {
    Path root = Files.createDirectories(temp.resolve("renew-before-mutation-claim"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.writeString(docs.resolve("target.txt"), "target");
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationRecoveryRepository repository = recoveryRepository(records);
    SharedFolderMutationService crashing = new SharedFolderMutationService(
        access, properties, WindowsSharedFolderMutationBoundary.inactive(), repository) {
      @Override
      protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
        if (state == SharedFolderMutationRecoveryState.PREPARED) {
          throw new AssertionError("simulated process death");
        }
      }
    };
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashing.move(
        new SharedFolderMoveRequest("docs/source.txt", "docs", "target.txt",
            crashing.observedToken("docs/source.txt"), true,
            crashing.observedToken("docs/target.txt"))))
        .isInstanceOf(AssertionError.class);
    SharedFolderMutationRecovery stored = records.values().iterator().next();
    String writerToken = stored.getOperationLeaseToken();
    stored.setOperationLeaseExpiresAt(Instant.EPOCH);
    Instant renewedUntil = Instant.now().plusSeconds(300);
    org.mockito.Mockito.doAnswer(invocation -> {
      SharedFolderMutationRecovery current = records.get(invocation.getArgument(0));
      current.setOperationLeaseExpiresAt(renewedUntil);
      current.setUpdatedAt(Instant.now());
      return 0L;
    }).when(repository).claimExpiredOperationLease(
        any(), any(), any(), any(), any(), any(), any());

    new SharedFolderMutationService(
        access, properties, WindowsSharedFolderMutationBoundary.inactive(), repository)
        .reconcileStartup();

    assertThat(records).hasSize(1);
    assertThat(records.values().iterator().next().getOperationLeaseToken()).isEqualTo(writerToken);
    assertThat(records.values().iterator().next().getOperationLeaseExpiresAt())
        .isEqualTo(renewedUntil);
    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("target");
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

        records.values().forEach(
            record -> record.setOperationLeaseExpiresAt(Instant.now().minusSeconds(1)));

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
  @EnabledOnOs(OS.WINDOWS)
  void nativeExplicitReplacementTargetDisappearanceConflictsBeforeSourceMutation() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-disappeared-target"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.writeString(docs.resolve("target.txt"), "target");
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    Files.createDirectories(properties.systemRoot());
    WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
    boundary.initialize();
    try {
      AtomicBoolean removeTarget = new AtomicBoolean(true);
      SharedFolderMutationService mutations = new SharedFolderMutationService(
          access, properties, boundary, recoveryRepository(records)) {
        @Override
        protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
          if (state == SharedFolderMutationRecoveryState.PREPARED
              && removeTarget.compareAndSet(true, false)) {
            try {
              Files.delete(docs.resolve("target.txt"));
            } catch (java.io.IOException exception) {
              throw new AssertionError(exception);
            }
          }
        }
      };
      String sourceToken = mutations.observedToken("docs/source.txt");
      String targetToken = mutations.observedToken("docs/target.txt");

      assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
          "docs/source.txt", "docs", "target.txt", sourceToken, true, targetToken)));

      assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
      assertThat(Files.notExists(docs.resolve("target.txt"))).isTrue();
    } finally {
      boundary.destroy();
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void explicitReplacementRequiresTheObservedTargetAtInitialLookupInPortableAndNativeModes()
      throws Exception {
    for (boolean nativeMode : java.util.List.of(false, true)) {
      Path root = Files.createDirectories(temp.resolve("initial-target-missing-" + nativeMode));
      Path docs = Files.createDirectories(root.resolve("docs"));
      Files.writeString(docs.resolve("source.txt"), "source");
      Files.writeString(docs.resolve("target.txt"), "target");
      SharedFolderProperties properties = properties(root);
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      WindowsSharedFolderMutationBoundary boundary = nativeMode
          ? new WindowsSharedFolderMutationBoundary(properties)
          : WindowsSharedFolderMutationBoundary.inactive();
      if (nativeMode) boundary.initialize();
      try {
        SharedFolderMutationService mutations = new SharedFolderMutationService(
            access, properties, boundary, recoveryRepository(new ConcurrentHashMap<>()));
        String sourceToken = mutations.observedToken("docs/source.txt");
        String targetToken = mutations.observedToken("docs/target.txt");
        Files.delete(docs.resolve("target.txt"));

        assertStatus(409, () -> mutations.move(new SharedFolderMoveRequest(
            "docs/source.txt", "docs", "target.txt", sourceToken, true, targetToken)));

        assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
        assertThat(Files.notExists(docs.resolve("target.txt"))).isTrue();
      } finally {
        if (nativeMode) boundary.destroy();
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
        0, 503,
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
  void nativeRecoveryPropagatesUnavailableMetadataInsteadOfTreatingItAsMissing() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-recovery-unavailable"));
    SharedFolderProperties properties = properties(root);
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationRecovery recovery = new SharedFolderMutationRecovery();
    recovery.setId("recovery-1");
    recovery.setOwnerId("account-1");
    recovery.setSourcePath("source.txt");
    recovery.setDestinationParentPath("");
    recovery.setName("target.txt");
    recovery.setSourceIdentity("source-identity");
    recovery.setTargetIdentity("target-identity");
    recovery.setQuarantineKey("quarantine-key");
    recovery.setNativeMode(true);
    recovery.setState(SharedFolderMutationRecoveryState.PREPARED);
    recovery.setOperationLeaseToken("expired-token");
    recovery.setOperationLeaseExpiresAt(Instant.EPOCH);
    recovery.setCreatedAt(Instant.EPOCH);
    recovery.setUpdatedAt(Instant.EPOCH);
    records.put(recovery.getId(), recovery);
    SharedFolderMutationRecoveryRepository repository = recoveryRepository(records);
    WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
    when(boundary.nativeMode()).thenReturn(true);
    when(boundary.metadata(any())).thenThrow(
        new dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException(
            "native metadata unavailable", 0));
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        access, properties, boundary, repository);

    assertStatus(503, mutations::reconcileStartup);

    assertThat(records).containsKey(recovery.getId());
    verify(boundary, org.mockito.Mockito.never()).restoreQuarantine(
        any(), any(), any(), any());
    verify(boundary, org.mockito.Mockito.never()).deleteQuarantine(any(), any());
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

  @Test
  void durablePortableReplacementRejectsNonEmptyDirectoryBeforeDisplacement() throws Exception {
    Path root = Files.createDirectories(temp.resolve("durable-nonempty-replace"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Path target = Files.createDirectories(docs.resolve("target"));
    Files.writeString(target.resolve("child.txt"), "child");
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        access, properties(root), WindowsSharedFolderMutationBoundary.inactive(),
        recoveryRepository(records));

    assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
        "docs/source.txt", "docs", "target", mutations.observedToken("docs/source.txt"),
        true, mutations.observedToken("docs/target"))));

    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.readString(target.resolve("child.txt"))).isEqualTo("child");
    assertThat(records).isEmpty();
  }

  @Test
  void durablePortableReplacementRechecksQuarantinedDirectoryBeforeMovingSource() throws Exception {
    Path root = Files.createDirectories(temp.resolve("durable-directory-recheck"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.createDirectories(docs.resolve("target"));
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        access, properties, WindowsSharedFolderMutationBoundary.inactive(),
        recoveryRepository(records)) {
      @Override
      protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
        if (state == SharedFolderMutationRecoveryState.TARGET_QUARANTINED) {
          try {
            String key = records.values().iterator().next().getQuarantineKey();
            Files.writeString(properties.systemRoot().resolve("shared-folder-mutation-quarantine")
                .resolve(key).resolve("late-child.txt"), "late");
          } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
          }
        }
      }
    };

    assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
        "docs/source.txt", "docs", "target", mutations.observedToken("docs/source.txt"),
        true, mutations.observedToken("docs/target"))));

    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.readString(docs.resolve("target/late-child.txt"))).isEqualTo("late");
  }

  @Test
  void durablePortableReplacementDetectsSameSizeTargetEditAndTargetDisappearance() throws Exception {
    for (boolean disappear : java.util.List.of(false, true)) {
      Path root = Files.createDirectories(temp.resolve("durable-target-change-" + disappear));
      Path docs = Files.createDirectories(root.resolve("docs"));
      Files.writeString(docs.resolve("source.txt"), "source");
      Files.writeString(docs.resolve("target.txt"), "AAAA");
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
      AtomicBoolean changed = new AtomicBoolean();
      SharedFolderMutationService mutations = new SharedFolderMutationService(
          access, properties(root), WindowsSharedFolderMutationBoundary.inactive(),
          recoveryRepository(records)) {
        @Override
        protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
          if (state == SharedFolderMutationRecoveryState.PREPARED && changed.compareAndSet(false, true)) {
            try {
              if (disappear) Files.delete(docs.resolve("target.txt"));
              else Files.writeString(docs.resolve("target.txt"), "BBBB");
            } catch (java.io.IOException exception) {
              throw new AssertionError(exception);
            }
          }
        }
      };
      String sourceToken = mutations.observedToken("docs/source.txt");
      String targetToken = mutations.observedToken("docs/target.txt");

      assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
          "docs/source.txt", "docs", "target.txt", sourceToken, true, targetToken)));

      assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
      if (disappear) assertThat(Files.notExists(docs.resolve("target.txt"))).isTrue();
      else assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("BBBB");
    }
  }

  @Test
  void caseOnlyRenameFailureNeverStrandsSourceUnderVisibleUuid() throws Exception {
    Path root = Files.createDirectories(temp.resolve("case-only-atomic-failure"));
    Files.writeString(root.resolve("Report.txt"), "content");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderMutationService mutations = new SharedFolderMutationService(access, properties(root)) {
      @Override
      protected void moveCaseOnlyAtomically(Path source, Path target) throws java.io.IOException {
        throw new java.io.IOException("atomic case rename failed");
      }
    };
    String token = mutations.observedToken("Report.txt");

    assertStatus(404, () -> mutations.rename(
        new SharedFolderRenameRequest("Report.txt", "report.txt", token)));

    assertThat(Files.exists(root.resolve("Report.txt"))).isTrue();
    try (var names = Files.list(root)) {
      assertThat(names.map(path -> path.getFileName().toString()).toList())
          .allMatch(name -> !name.startsWith("__shared-folder-case-"));
    }
  }

  @Test
  void durableReplacementStopsBeforeQuarantineWhenTheFencedScanLosesItsLease() throws Exception {
    Path root = Files.createDirectories(temp.resolve("lost-writer-lease"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.write(docs.resolve("target.bin"), new byte[256 * 1024]);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationRecoveryRepository repository = recoveryRepository(records);
    org.mockito.Mockito.doReturn(0L).when(repository)
        .renewOperationLease(any(), any(), any(), any(), any());
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        access, properties(root), WindowsSharedFolderMutationBoundary.inactive(), repository);

    assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
        "docs/source.txt", "docs", "target.bin",
        mutations.observedToken("docs/source.txt"), true,
        mutations.observedToken("docs/target.bin"))));

    verify(repository, org.mockito.Mockito.atLeastOnce())
        .renewOperationLease(any(), any(), any(), any(), any());
    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.size(docs.resolve("target.bin"))).isEqualTo(256 * 1024);
    try (var quarantined = Files.list(properties(root).systemRoot()
        .resolve("shared-folder-mutation-quarantine"))) {
      assertThat(quarantined).isEmpty();
    }
  }

  @Test
  void slowSubMegabyteMutationScanRenewsByTimeBeforeTheOriginalShortLeaseExpires()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("timed-writer-heartbeat"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.write(docs.resolve("target.bin"), new byte[32 * 1024]);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationRecoveryRepository repository = recoveryRepository(records);
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-07-17T12:00:00Z"));
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        access, properties(root), WindowsSharedFolderMutationBoundary.inactive(), repository) {
      @Override protected Instant leaseNow() {
        return clock.getAndUpdate(value -> value.plusMillis(40));
      }
      @Override protected Duration operationLeaseDuration() { return Duration.ofMillis(100); }
    };

    mutations.move(new SharedFolderMoveRequest(
        "docs/source.txt", "docs", "target.bin",
        mutations.observedToken("docs/source.txt"), true,
        mutations.observedToken("docs/target.bin")));

    verify(repository, org.mockito.Mockito.atLeast(4))
        .renewOperationLease(any(), any(), any(), any(), any());
    assertThat(Files.readString(docs.resolve("target.bin"))).isEqualTo("source");
    assertThat(records).isEmpty();
  }

  @Test
  void expiredMutationRecoveryStopsWhenItsRecoveryTokenIsLostDuringIdentityScan()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("lost-recovery-lease"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.write(docs.resolve("target.bin"), new byte[256 * 1024]);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderMutationRecoveryRepository repository = recoveryRepository(records);
    SharedFolderMutationService crashing = new SharedFolderMutationService(
        access, properties(root), WindowsSharedFolderMutationBoundary.inactive(), repository) {
      @Override
      protected void afterPhysicalMutationTransition(SharedFolderMutationRecoveryState state) {
        if (state == SharedFolderMutationRecoveryState.TARGET_QUARANTINED) {
          throw new AssertionError("simulated process death");
        }
      }
    };
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashing.move(
        new SharedFolderMoveRequest("docs/source.txt", "docs", "target.bin",
            crashing.observedToken("docs/source.txt"), true,
            crashing.observedToken("docs/target.bin"))))
        .isInstanceOf(AssertionError.class);
    SharedFolderMutationRecovery durable = records.values().iterator().next();
    durable.setOperationLeaseExpiresAt(Instant.EPOCH);
    org.mockito.Mockito.doReturn(0L).when(repository)
        .renewOperationLease(any(), any(), any(), any(), any());

    SharedFolderMutationService recovering = new SharedFolderMutationService(
        access, properties(root), WindowsSharedFolderMutationBoundary.inactive(), repository);
    assertConflict(recovering::reconcileStartup);

    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.notExists(docs.resolve("target.bin"))).isTrue();
    Path quarantine = properties(root).systemRoot().resolve("shared-folder-mutation-quarantine")
        .resolve(durable.getQuarantineKey());
    assertThat(Files.size(quarantine)).isEqualTo(256 * 1024);
    assertThat(records).hasSize(1);
  }

  @Test
  void durableReplacementRacerLeavesRestorePendingWithEveryPayloadPreserved()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("durable-restore-racer"));
    Path docs = Files.createDirectories(root.resolve("docs"));
    Files.writeString(docs.resolve("source.txt"), "source");
    Files.writeString(docs.resolve("target.txt"), "observed-target");
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderMutationRecovery> records = new ConcurrentHashMap<>();
    SharedFolderProperties properties = properties(root);
    AtomicBoolean injectedRacer = new AtomicBoolean();
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        access, properties, WindowsSharedFolderMutationBoundary.inactive(),
        recoveryRepository(records)) {
      @Override
      protected void moveAtomically(Path source, Path target, boolean replace)
          throws java.io.IOException {
        if (source.getFileName().toString().equals("source.txt")
            && target.getFileName().toString().equals("target.txt")
            && injectedRacer.compareAndSet(false, true)) {
          Files.writeString(target, "racer");
          throw new java.nio.file.FileAlreadyExistsException(target.toString());
        }
        super.moveAtomically(source, target, replace);
      }
    };

    assertConflict(() -> mutations.move(new SharedFolderMoveRequest(
        "docs/source.txt", "docs", "target.txt",
        mutations.observedToken("docs/source.txt"), true,
        mutations.observedToken("docs/target.txt"))));

    assertThat(Files.readString(docs.resolve("source.txt"))).isEqualTo("source");
    assertThat(Files.readString(docs.resolve("target.txt"))).isEqualTo("racer");
    assertThat(injectedRacer).isTrue();
    assertThat(records).hasSize(1);
    SharedFolderMutationRecovery recovery = records.values().iterator().next();
    assertThat(recovery.getState()).isEqualTo(SharedFolderMutationRecoveryState.RESTORE_PENDING);
    assertThat(Files.readString(properties.systemRoot()
        .resolve("shared-folder-mutation-quarantine")
        .resolve(recovery.getQuarantineKey()))).isEqualTo("observed-target");
  }

  @Test
  void caseOnlySpellingOnCaseSensitiveProviderUsesStrictNoReplaceAndPreservesCollision()
      throws Exception {
    Path archive = temp.resolve("case-sensitive-spelling.zip");
    try (var fileSystem = java.nio.file.FileSystems.newFileSystem(
        java.net.URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
      Path root = Files.createDirectory(fileSystem.getPath("/shared"));
      Path system = Files.createDirectory(fileSystem.getPath("/system"));
      Files.writeString(root.resolve("Report.txt"), "source");
      Files.writeString(root.resolve("report.txt"), "collision");
      SharedFolderMutationService mutations = new SharedFolderMutationService(
          mock(SharedFolderAccessService.class), properties(root, system)) {
        @Override protected dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver
            portableResolver() { return caseSensitiveResolver(root); }
      };

      caseSensitiveResolver(root).readHandle(
          caseSensitiveResolver(root).existing("Report.txt")).attributes();
      String observed = mutations.observedToken("Report.txt");
      assertConflict(() -> mutations.rename(new SharedFolderRenameRequest(
          "Report.txt", "report.txt", observed)));

      assertThat(Files.readString(root.resolve("Report.txt"))).isEqualTo("source");
      assertThat(Files.readString(root.resolve("report.txt"))).isEqualTo("collision");
    }
  }

  @Test
  void caseOnlySpellingTargetRaceOnCaseSensitiveProviderPreservesBothFiles()
      throws Exception {
    Path archive = temp.resolve("case-sensitive-spelling-race.zip");
    try (var fileSystem = java.nio.file.FileSystems.newFileSystem(
        java.net.URI.create("jar:" + archive.toUri()), Map.of("create", "true"))) {
      Path root = Files.createDirectory(fileSystem.getPath("/shared"));
      Path system = Files.createDirectory(fileSystem.getPath("/system"));
      Files.writeString(root.resolve("Report.txt"), "source");
      SharedFolderMutationService mutations = new SharedFolderMutationService(
          mock(SharedFolderAccessService.class), properties(root, system)) {
        @Override protected dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver
            portableResolver() { return caseSensitiveResolver(root); }
        @Override protected void beforePortableNoReplaceMove(Path source, Path target)
            throws java.io.IOException {
          Files.writeString(target, "racer");
        }
      };

      String observed = mutations.observedToken("Report.txt");
      assertConflict(() -> mutations.rename(new SharedFolderRenameRequest(
          "Report.txt", "report.txt", observed)));

      assertThat(Files.readString(root.resolve("Report.txt"))).isEqualTo("source");
      assertThat(Files.readString(root.resolve("report.txt"))).isEqualTo("racer");
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void portableCaseInsensitiveProviderRenamesOnlyTheSameObjectSpelling() throws Exception {
    Path root = Files.createDirectories(temp.resolve("portable-case-insensitive"));
    Files.writeString(root.resolve("Report.txt"), "source");
    SharedFolderMutationService mutations = new SharedFolderMutationService(
        mock(SharedFolderAccessService.class), properties(root));

    mutations.rename(new SharedFolderRenameRequest(
        "Report.txt", "report.txt", mutations.observedToken("Report.txt")));

    assertThat(Files.readString(root.resolve("report.txt"))).isEqualTo("source");
    assertThat(Files.list(root).map(path -> path.getFileName().toString()).toList())
        .containsExactly("report.txt");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void explicitReplacementPreservesObservedTargetSpellingInPortableAndNativeModes()
      throws Exception {
    for (boolean nativeMode : java.util.List.of(false, true)) {
      Path root = Files.createDirectories(temp.resolve("canonical-replacement-" + nativeMode));
      Files.writeString(root.resolve("source.txt"), "source");
      Files.writeString(root.resolve("Target.txt"), "target");
      SharedFolderProperties properties = properties(root);
      WindowsSharedFolderMutationBoundary boundary = nativeMode
          ? new WindowsSharedFolderMutationBoundary(properties)
          : WindowsSharedFolderMutationBoundary.inactive();
      if (nativeMode) boundary.initialize();
      try {
        SharedFolderMutationService mutations = new SharedFolderMutationService(
            mock(SharedFolderAccessService.class), properties, boundary);

        SharedDirectoryEntry moved = mutations.move(new SharedFolderMoveRequest(
            "source.txt", "", "target.txt", mutations.observedToken("source.txt"), true,
            mutations.observedToken("Target.txt")));

        assertThat(moved.name()).isEqualTo("Target.txt");
        assertThat(moved.path()).isEqualTo("Target.txt");
        assertThat(Files.readString(root.resolve("Target.txt"))).isEqualTo("source");
        assertThat(Files.list(root).map(path -> path.getFileName().toString()).toList())
            .containsExactly("Target.txt");
      } finally {
        if (nativeMode) boundary.destroy();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private SharedFolderMutationRecoveryRepository recoveryRepository(
      Map<String, SharedFolderMutationRecovery> records) {
    SharedFolderMutationRecoveryRepository repository =
        mock(SharedFolderMutationRecoveryRepository.class);
    when(repository.save(any(SharedFolderMutationRecovery.class))).thenAnswer(invocation -> {
      synchronized (records) {
        SharedFolderMutationRecovery recovery = invocation.getArgument(0);
        SharedFolderMutationRecovery existing = records.get(recovery.getId());
        if (existing != null && !java.util.Objects.equals(
            existing.getVersion(), recovery.getVersion())) {
          throw new org.springframework.dao.OptimisticLockingFailureException("stale recovery");
        }
        SharedFolderMutationRecovery saved = recovery.copy();
        saved.setVersion(existing == null ? 0L : existing.getVersion() + 1L);
        records.put(saved.getId(), saved);
        return saved.copy();
      }
    });
    when(repository.findTop100ByOwnerIdOrderByUpdatedAtAsc(any(String.class))).thenAnswer(
        invocation -> records.values().stream()
            .filter(record -> record.getOwnerId().equals(invocation.getArgument(0)))
            .map(SharedFolderMutationRecovery::copy).toList());
    when(repository.findTop100ByOrderByUpdatedAtAsc()).thenAnswer(
        invocation -> records.values().stream().map(SharedFolderMutationRecovery::copy).toList());
    when(repository.findById(any(String.class))).thenAnswer(invocation ->
        java.util.Optional.ofNullable(records.get(invocation.getArgument(0)))
            .map(SharedFolderMutationRecovery::copy));
    org.mockito.Mockito.doAnswer(invocation -> {
      synchronized (records) {
        SharedFolderMutationRecovery current = records.get(invocation.getArgument(0));
        if (current == null
            || !java.util.Objects.equals(current.getOperationLeaseToken(), invocation.getArgument(1))
            || current.getState() != invocation.getArgument(2)) {
          return 0L;
        }
        current.setOperationLeaseExpiresAt(invocation.getArgument(3));
        current.setUpdatedAt(invocation.getArgument(4));
        return 1L;
      }
    }).when(repository).renewOperationLease(any(), any(), any(), any(), any());
    org.mockito.Mockito.doAnswer(invocation -> {
      synchronized (records) {
        SharedFolderMutationRecovery current = records.get(invocation.getArgument(0));
        Instant now = invocation.getArgument(3);
        if (current == null
            || !java.util.Objects.equals(
                current.getOperationLeaseToken(), invocation.getArgument(1))
            || current.getState() != invocation.getArgument(2)
            || current.getOperationLeaseExpiresAt() != null
                && current.getOperationLeaseExpiresAt().isAfter(now)) {
          return 0L;
        }
        current.setOperationLeaseToken(invocation.getArgument(4));
        current.setOperationLeaseExpiresAt(invocation.getArgument(5));
        current.setUpdatedAt(invocation.getArgument(6));
        current.setVersion(current.getVersion() == null ? 0L : current.getVersion() + 1L);
        return 1L;
      }
    }).when(repository).claimExpiredOperationLease(
        any(), any(), any(), any(), any(), any(), any());
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
    try {
      return properties(root, Files.createDirectories(temp.resolve("system")));
    } catch (java.io.IOException exception) {
      throw new AssertionError("test system root could not be created", exception);
    }
  }

  private dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver caseSensitiveResolver(
      Path root) {
    var boundary = new dev.christopherbell.sharedfolder.fs.NioSharedFolderFileSystemBoundary(
        canonicalPath -> false) {
      @Override public boolean isMountPoint(Path path) { return false; }
      @Override public Object dosAttributesNoFollow(Path path) { return 0; }
      @Override public boolean sameFileStore(Path first, Path second) { return true; }
    };
    return new dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver(root, boundary);
  }

  private SharedFolderProperties properties(Path root, Path systemRoot) {
    return new SharedFolderProperties(
        root,
        systemRoot,
        DataSize.ofGigabytes(10),
        DataSize.ofMegabytes(8),
        DataSize.ofBytes(1),
        DataSize.ofGigabytes(1),
        Duration.ofDays(30),
        Duration.ofDays(180),
        true);
  }
}

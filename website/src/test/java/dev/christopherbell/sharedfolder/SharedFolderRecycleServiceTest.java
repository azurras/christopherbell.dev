package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileIdentity;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeFileMetadata;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleRepository;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleState;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderRecycleServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

  @TempDir Path temp;

  @Test
  void deleteMovesObservedItemToPrivateRecycleAndAdminRestoresIt() throws Exception {
    Fixture fixture = fixture();
    Path source = Files.createDirectories(fixture.root.resolve("docs")).resolve("report.pdf");
    Files.writeString(source, "report");
    String observed = fixture.mutations.observedToken("docs/report.pdf");

    SharedFolderRecycleItem item = fixture.recycle.recycle(
        new SharedFolderDeleteRequest("docs/report.pdf", observed));

    assertThat(source).doesNotExist();
    assertThat(item.originalPath()).isEqualTo("docs/report.pdf");
    assertThat(item.deletedByAccountId()).isEqualTo("writer-1");
    assertThat(item.state()).isEqualTo(SharedFolderRecycleState.RECYCLED);
    assertThat(item.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(fixture.system.resolve("shared-folder-recycle").resolve(item.payloadKey()))
        .hasContent("report");

    var restored = fixture.recycle.restore(item.id(), false);

    verify(fixture.access).requireAdmin();
    assertThat(restored.path()).isEqualTo("docs/report.pdf");
    assertThat(source).hasContent("report");
    assertThat(fixture.records).doesNotContainKey(item.id());
  }

  @Test
  void restoreConflictsUnlessAdminExplicitlyReplacesTheCurrentTarget() throws Exception {
    Fixture fixture = fixture();
    Path docs = Files.createDirectories(fixture.root.resolve("docs"));
    Files.writeString(docs.resolve("report.pdf"), "recycled");
    String observed = fixture.mutations.observedToken("docs/report.pdf");
    SharedFolderRecycleItem item = fixture.recycle.recycle(
        new SharedFolderDeleteRequest("docs/report.pdf", observed));
    Files.writeString(docs.resolve("report.pdf"), "current");

    assertStatus(409, () -> fixture.recycle.restore(item.id(), false));
    assertThat(docs.resolve("report.pdf")).hasContent("current");

    fixture.recycle.restore(item.id(), true);

    assertThat(docs.resolve("report.pdf")).hasContent("recycled");
    assertThat(fixture.records).doesNotContainKey(item.id());
  }

  @Test
  void purgeRequiresAdminTypedConfirmationAndCleanupOnlyPurgesExpiredItems() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.root.resolve("expired.txt"), "old");
    String expiredToken = fixture.mutations.observedToken("expired.txt");
    SharedFolderRecycleItem expired = fixture.recycle.recycle(
        new SharedFolderDeleteRequest("expired.txt", expiredToken));
    fixture.records.put(expired.id(), expired.withExpiresAt(NOW.minusSeconds(1)));

    Files.writeString(fixture.root.resolve("current.txt"), "current");
    String currentToken = fixture.mutations.observedToken("current.txt");
    SharedFolderRecycleItem current = fixture.recycle.recycle(
        new SharedFolderDeleteRequest("current.txt", currentToken));

    assertThat(fixture.recycle.cleanupExpired()).isEqualTo(1);
    assertThat(fixture.records).containsKey(current.id()).doesNotContainKey(expired.id());
    assertThat(fixture.system.resolve("shared-folder-recycle").resolve(expired.payloadKey()))
        .doesNotExist();
    verify(fixture.audit).recordSystem(
        "RETENTION_PURGE", expired.originalPath(), expired.size(), "accepted", null);

    assertStatus(400, () -> fixture.recycle.purge(current.id(), "PURGE"));
    assertStatus(400, () -> fixture.recycle.purge(current.id(), current.id()));
    fixture.recycle.purge(current.id(), "PURGE " + current.id());

    verify(fixture.access, org.mockito.Mockito.times(3)).requireAdmin();
    assertThat(fixture.records).doesNotContainKey(current.id());
  }

  @Test
  void retentionCleanupAuditsFailureAndContinuesWithLaterExpiredItems() throws Exception {
    Fixture fixture = fixture();
    Files.writeString(fixture.root.resolve("changed.txt"), "original");
    SharedFolderRecycleItem changed = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "changed.txt", fixture.mutations.observedToken("changed.txt")));
    changed = changed.withExpiresAt(NOW.minusSeconds(1));
    fixture.records.put(changed.id(), changed);
    Files.writeString(fixture.system.resolve("shared-folder-recycle").resolve(changed.payloadKey()),
        "changed after recycle");

    Files.writeString(fixture.root.resolve("purgeable.txt"), "purgeable");
    SharedFolderRecycleItem purgeable = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "purgeable.txt", fixture.mutations.observedToken("purgeable.txt")));
    purgeable = purgeable.withExpiresAt(NOW.minusSeconds(1));
    fixture.records.put(purgeable.id(), purgeable);

    assertThat(fixture.recycle.cleanupExpired()).isEqualTo(1);
    assertThat(fixture.records).containsKey(changed.id()).doesNotContainKey(purgeable.id());
    verify(fixture.audit).recordSystemFailure(
        org.mockito.ArgumentMatchers.eq("RETENTION_PURGE"),
        org.mockito.ArgumentMatchers.eq(changed.originalPath()),
        org.mockito.ArgumentMatchers.eq(changed.size()),
        org.mockito.ArgumentMatchers.any(RuntimeException.class));
    verify(fixture.audit).recordSystem(
        "RETENTION_PURGE", purgeable.originalPath(), purgeable.size(), "accepted", null);
  }

  @Test
  void missingPayloadExpiredMetadataAndUnsafeStoredPathsFailClosed() throws Exception {
    Fixture fixture = fixture();
    SharedFolderRecycleItem missing = new SharedFolderRecycleItem(
        "missing-id", "missing.txt", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        "missing-payload", 1, false, "fingerprint", SharedFolderRecycleState.RECYCLED);
    fixture.records.put(missing.id(), missing);

    assertStatus(404, () -> fixture.recycle.restore(missing.id(), false));

    SharedFolderRecycleItem unsafe = new SharedFolderRecycleItem(
        "unsafe-id", "../escape.txt", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        "unsafe-payload", 1, false, "fingerprint", SharedFolderRecycleState.RECYCLED);
    fixture.records.put(unsafe.id(), unsafe);
    Files.createDirectories(fixture.system.resolve("shared-folder-recycle"));
    Files.writeString(fixture.system.resolve("shared-folder-recycle/unsafe-payload"), "unsafe");

    assertStatus(404, () -> fixture.recycle.restore(unsafe.id(), false));
    assertThat(temp.resolve("escape.txt")).doesNotExist();
  }

  @Test
  void startupReconcilesPreparingRestoringCompletedRestoreAndPurgingStates() throws Exception {
    Fixture fixture = fixture();
    Path recycleRoot = Files.createDirectories(fixture.system.resolve("shared-folder-recycle"));
    Path replacedRoot = Files.createDirectories(
        fixture.system.resolve("shared-folder-recycle-replaced"));

    Files.writeString(fixture.root.resolve("preparing.txt"), "preparing");
    SharedFolderRecycleItem preparing = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "preparing.txt", fixture.mutations.observedToken("preparing.txt")));
    fixture.records.put(preparing.id(), preparing.withState(SharedFolderRecycleState.PREPARING));

    Files.writeString(fixture.root.resolve("pending.txt"), "payload");
    SharedFolderRecycleItem pending = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "pending.txt", fixture.mutations.observedToken("pending.txt")));
    Files.writeString(fixture.root.resolve("pending.txt"), "current");
    String currentToken = fixture.mutations.observedToken("pending.txt");
    String replacementKey = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    Files.move(fixture.root.resolve("pending.txt"), replacedRoot.resolve(replacementKey));
    fixture.records.put(pending.id(), pending.withRestore(replacementKey, currentToken));

    Files.writeString(fixture.root.resolve("completed.txt"), "restored");
    Path completedPath = fixture.root.resolve("completed.txt");
    String restoredToken = fixture.mutations.observedToken("completed.txt");
    var completedAttributes = Files.readAttributes(
        completedPath, java.nio.file.attribute.BasicFileAttributes.class);
    String completedIdentity =
        dev.christopherbell.sharedfolder.fs.PortableSharedFolderPrivateBoundary
            .stableIdentity(completedPath)
        + ":" + completedAttributes.isDirectory() + ":" + completedAttributes.isRegularFile();
    String completedPayload = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    String completedReplacement = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    Files.writeString(replacedRoot.resolve(completedReplacement), "displaced");
    String completedReplacementFingerprint =
        dev.christopherbell.sharedfolder.service.SharedFolderObservedItemTokens.token(
            "completed.txt", Files.readAttributes(
                replacedRoot.resolve(completedReplacement),
                java.nio.file.attribute.BasicFileAttributes.class));
    SharedFolderRecycleItem completed = new SharedFolderRecycleItem(
        "completed-id", "completed.txt", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        completedPayload, 8, false, restoredToken, SharedFolderRecycleState.RESTORING,
        completedReplacement, completedReplacementFingerprint, completedIdentity);
    fixture.records.put(completed.id(), completed);

    SharedFolderRecycleItem purging = new SharedFolderRecycleItem(
        "purging-id", "purged.txt", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", 1, false, "fingerprint",
        SharedFolderRecycleState.PURGING, null, null);
    fixture.records.put(purging.id(), purging);

    assertThat(fixture.recycle.reconcilePending()).isEqualTo(4);

    assertThat(fixture.records.get(preparing.id()).state())
        .isEqualTo(SharedFolderRecycleState.RECYCLED);
    assertThat(fixture.root.resolve("pending.txt")).hasContent("current");
    assertThat(fixture.records.get(pending.id()).state())
        .isEqualTo(SharedFolderRecycleState.RECYCLED);
    assertThat(fixture.records).doesNotContainKeys(completed.id(), purging.id());
    assertThat(replacedRoot.resolve(completedReplacement)).doesNotExist();
    assertThat(recycleRoot.resolve(preparing.payloadKey())).exists();
  }

  @Test
  void rootCannotBeRecycledAndPermanentPurgeRemovesOnlyTheRetainedDirectoryTree() throws Exception {
    Fixture fixture = fixture();
    Files.createDirectories(fixture.root.resolve("folder/sub"));
    Files.writeString(fixture.root.resolve("folder/sub/child.txt"), "child");

    assertStatus(400, () -> fixture.recycle.recycle(new SharedFolderDeleteRequest("", "token")));
    assertThat(fixture.root).isDirectory();

    SharedFolderRecycleItem directory = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "folder", fixture.mutations.observedToken("folder")));
    fixture.recycle.purge(directory.id(), "PURGE " + directory.id());

    assertThat(fixture.system.resolve("shared-folder-recycle").resolve(directory.payloadKey()))
        .doesNotExist();
    assertThat(fixture.root).isDirectory();
  }

  @Test
  void reconciliationLeavesAnAmbiguousRecordAndContinuesWithLaterRecoverableRecords()
      throws Exception {
    Fixture fixture = fixture();
    SharedFolderRecycleItem ambiguous = new SharedFolderRecycleItem(
        "ambiguous-id", "../escape.txt", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1, false, "fingerprint",
        SharedFolderRecycleState.PREPARING);
    Files.writeString(fixture.root.resolve("recoverable.txt"), "recoverable");
    SharedFolderRecycleItem recoverable = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "recoverable.txt", fixture.mutations.observedToken("recoverable.txt")));
    recoverable = recoverable.withState(SharedFolderRecycleState.PREPARING);
    fixture.records.clear();
    fixture.records.put(ambiguous.id(), ambiguous);
    fixture.records.put(recoverable.id(), recoverable);

    assertThat(fixture.recycle.reconcilePending()).isEqualTo(1);

    assertThat(fixture.records).containsKey(ambiguous.id());
    assertThat(fixture.records.get(recoverable.id()).state())
        .isEqualTo(SharedFolderRecycleState.RECYCLED);
  }

  @Test
  void replacementJournalFieldsMustBePresentTogether() {
    assertThatThrownBy(() -> new SharedFolderRecycleItem(
        "item-id", "file.txt", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1, false, "fingerprint",
        SharedFolderRecycleState.RESTORING, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Replacement journal");
  }

  @Test
  void reconciliationCompletesAPartiallyDeletedDirectoryPurge() throws Exception {
    Fixture fixture = fixture();
    Path folder = Files.createDirectories(fixture.root.resolve("partial/sub"));
    Files.writeString(folder.resolve("first.txt"), "first");
    Files.writeString(folder.resolve("second.txt"), "second");
    SharedFolderRecycleItem item = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "partial", fixture.mutations.observedToken("partial")));
    fixture.records.put(item.id(), item.withState(SharedFolderRecycleState.PURGING));
    Path payload = fixture.system.resolve("shared-folder-recycle").resolve(item.payloadKey());
    Files.delete(payload.resolve("sub/first.txt"));

    assertThat(fixture.recycle.reconcilePending()).isEqualTo(1);
    assertThat(payload).doesNotExist();
    assertThat(fixture.records).doesNotContainKey(item.id());
  }

  @Test
  void reconciliationRecognizesAModifiedRestoredIdentityWithAndWithoutReplacement()
      throws Exception {
    Fixture fixture = fixture();
    Path recycleRoot = Files.createDirectories(fixture.system.resolve("shared-folder-recycle"));
    Path replacedRoot = Files.createDirectories(
        fixture.system.resolve("shared-folder-recycle-replaced"));

    Files.writeString(fixture.root.resolve("plain.txt"), "recycled");
    SharedFolderRecycleItem plain = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "plain.txt", fixture.mutations.observedToken("plain.txt")));
    Files.move(recycleRoot.resolve(plain.payloadKey()), fixture.root.resolve("plain.txt"));
    Files.writeString(fixture.root.resolve("plain.txt"), "edited after restore");
    fixture.records.put(plain.id(), plain.withRestore(null, null));

    Files.writeString(fixture.root.resolve("replace.txt"), "recycled");
    SharedFolderRecycleItem replacing = fixture.recycle.recycle(new SharedFolderDeleteRequest(
        "replace.txt", fixture.mutations.observedToken("replace.txt")));
    Files.writeString(fixture.root.resolve("replace.txt"), "displaced");
    String replacementFingerprint =
        dev.christopherbell.sharedfolder.service.SharedFolderObservedItemTokens.token(
            "replace.txt", Files.readAttributes(fixture.root.resolve("replace.txt"),
                java.nio.file.attribute.BasicFileAttributes.class));
    String replacementKey = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    Files.move(fixture.root.resolve("replace.txt"), replacedRoot.resolve(replacementKey));
    Files.move(recycleRoot.resolve(replacing.payloadKey()), fixture.root.resolve("replace.txt"));
    Files.writeString(fixture.root.resolve("replace.txt"), "edited replacement restore");
    fixture.records.put(replacing.id(), replacing.withRestore(
        replacementKey, replacementFingerprint));

    assertThat(fixture.recycle.reconcilePending()).isEqualTo(2);
    assertThat(fixture.records).doesNotContainKeys(plain.id(), replacing.id());
    assertThat(replacedRoot.resolve(replacementKey)).doesNotExist();
  }

  @Test
  void nativeReconciliationUsesStableIdentityAfterPartialPurgeAndCompletedRestore()
      throws Exception {
    byte[] fileId = new byte[16];
    fileId[0] = 7;
    NativeFileIdentity identity = new NativeFileIdentity(11, fileId);
    String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(fileId);
    String nativeIdentity = "11:" + encoded;
    String directoryStable = nativeIdentity + ":true:false";
    String directoryFull = directoryStable + ":2:" + NOW;
    String fileStable = nativeIdentity + ":false:true";
    String fileFull = fileStable + ":8:" + NOW;
    SharedFolderRecycleItem purging = new SharedFolderRecycleItem(
        "native-purge", "folder", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 2, true, directoryFull,
        SharedFolderRecycleState.PURGING, null, null, directoryStable);
    SharedFolderRecycleItem restoring = new SharedFolderRecycleItem(
        "native-restore", "edited.txt", "writer-1", NOW, NOW.plus(Duration.ofDays(30)),
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 8, false, fileFull,
        SharedFolderRecycleState.RESTORING, null, null, fileStable);
    WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
    when(boundary.nativeMode()).thenReturn(true);
    NativeFileMetadata partiallyPurged = new NativeFileMetadata(
        identity, true, false, 1, NOW.plusSeconds(1));
    when(boundary.recycleMetadata(purging.payloadKey())).thenReturn(partiallyPurged);
    when(boundary.recycleMetadata(restoring.payloadKey())).thenThrow(
        new NativeBoundaryException("missing", 2));
    when(boundary.metadata(purging.originalPath())).thenThrow(
        new NativeBoundaryException("missing", 2));
    when(boundary.metadata(restoring.originalPath())).thenReturn(new NativeFileMetadata(
        identity, false, true, 20, NOW.plusSeconds(2)));
    NativeFixture fixture = nativeFixture(boundary, purging, restoring);

    assertThat(fixture.recycle.reconcilePending()).isEqualTo(2);
    verify(boundary).deleteRecycleTree(purging.payloadKey(), partiallyPurged);
    assertThat(fixture.records).doesNotContainKeys(purging.id(), restoring.id());
  }

  private Fixture fixture() throws Exception {
    Path root = Files.createDirectories(temp.resolve("shared"));
    Path system = Files.createDirectories(temp.resolve("system"));
    SharedFolderProperties properties = new SharedFolderProperties(
        root, system, DataSize.ofGigabytes(10), DataSize.ofMegabytes(8),
        DataSize.ofBytes(1), DataSize.ofGigabytes(1), Duration.ofDays(30),
        Duration.ofDays(180), true);
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderAuditRecorder audit = mock(SharedFolderAuditRecorder.class);
    Account writer = account("writer-1", Role.USER);
    Account admin = account("admin-1", Role.ADMIN);
    when(access.requireWrite()).thenReturn(writer);
    when(access.requireAdmin()).thenReturn(admin);
    var records = new LinkedHashMap<String, SharedFolderRecycleItem>();
    SharedFolderRecycleRepository repository = mock(SharedFolderRecycleRepository.class);
    when(repository.save(any())).thenAnswer(invocation -> {
      SharedFolderRecycleItem item = invocation.getArgument(0);
      records.put(item.id(), item);
      return item;
    });
    when(repository.findById(any())).thenAnswer(invocation ->
        Optional.ofNullable(records.get(invocation.<String>getArgument(0))));
    when(repository.findByStateOrderByDeletedAtDesc(SharedFolderRecycleState.RECYCLED))
        .thenAnswer(ignored -> records.values().stream()
            .filter(item -> item.state() == SharedFolderRecycleState.RECYCLED).toList());
    when(repository.findByStateAndExpiresAtBefore(
        org.mockito.ArgumentMatchers.eq(SharedFolderRecycleState.RECYCLED), any()))
        .thenAnswer(invocation -> records.values().stream()
            .filter(item -> item.state() == SharedFolderRecycleState.RECYCLED)
            .filter(item -> item.expiresAt().isBefore(invocation.getArgument(1)))
            .toList());
    when(repository.findByStateIn(any())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      List<SharedFolderRecycleState> states = invocation.getArgument(0);
      return records.values().stream().filter(item -> states.contains(item.state())).toList();
    });
    org.mockito.Mockito.doAnswer(invocation -> {
      records.remove(invocation.<String>getArgument(0));
      return null;
    }).when(repository).deleteById(any());
    var boundary = WindowsSharedFolderMutationBoundary.inactive();
    var mutations = new SharedFolderMutationService(access, properties, boundary);
    var recycle = new SharedFolderRecycleService(
        access, properties, boundary, repository,
        Clock.fixed(NOW, ZoneOffset.UTC), audit);
    return new Fixture(root, system, access, records, mutations, recycle, audit);
  }

  private NativeFixture nativeFixture(
      WindowsSharedFolderMutationBoundary boundary, SharedFolderRecycleItem... items)
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-shared"));
    Path system = Files.createDirectories(temp.resolve("native-system"));
    SharedFolderProperties properties = new SharedFolderProperties(
        root, system, DataSize.ofGigabytes(10), DataSize.ofMegabytes(8),
        DataSize.ofBytes(1), DataSize.ofGigabytes(1), Duration.ofDays(30),
        Duration.ofDays(180), true);
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderAuditRecorder audit = mock(SharedFolderAuditRecorder.class);
    var records = new LinkedHashMap<String, SharedFolderRecycleItem>();
    for (SharedFolderRecycleItem item : items) records.put(item.id(), item);
    SharedFolderRecycleRepository repository = mock(SharedFolderRecycleRepository.class);
    when(repository.findByStateIn(any())).thenAnswer(invocation -> List.copyOf(records.values()));
    org.mockito.Mockito.doAnswer(invocation -> {
      records.remove(invocation.<String>getArgument(0));
      return null;
    }).when(repository).deleteById(any());
    SharedFolderRecycleService recycle = new SharedFolderRecycleService(
        access, properties, boundary, repository, Clock.fixed(NOW, ZoneOffset.UTC), audit);
    return new NativeFixture(records, recycle);
  }

  private Account account(String id, Role role) {
    Account account = new Account();
    account.setId(id);
    account.setRole(role);
    return account;
  }

  private void assertStatus(
      int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(expected));
  }

  private record Fixture(
      Path root,
      Path system,
      SharedFolderAccessService access,
      LinkedHashMap<String, SharedFolderRecycleItem> records,
      SharedFolderMutationService mutations,
      SharedFolderRecycleService recycle,
      SharedFolderAuditRecorder audit) {}

  private record NativeFixture(
      LinkedHashMap<String, SharedFolderRecycleItem> records,
      SharedFolderRecycleService recycle) {}
}

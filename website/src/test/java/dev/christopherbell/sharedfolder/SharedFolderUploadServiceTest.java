package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadStatus;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadCreateRequest;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.util.unit.DataSize;

class SharedFolderUploadServiceTest {
  @TempDir Path temp;

  @Test
  void acceptsOrderedIdenticalChunkRetryAndAtomicallyFinalizes() throws Exception {
    Path root = Files.createDirectories(temp.resolve("shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(sessions);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository, properties(root));
    byte[] first = "first".getBytes();

    SharedFolderUploadStatus session = uploads.create(new SharedFolderUploadCreateRequest(
        "", "video.mkv", first.length, sha256(first), null));
    uploads.append(session.id(), 0, new ByteArrayInputStream(first), sha256(first));
    SharedFolderUploadStatus retried = uploads.append(
        session.id(), 0, new ByteArrayInputStream(first), sha256(first));
    uploads.complete(session.id(), false);

    assertThat(retried.nextOffset()).isEqualTo(first.length);
    assertThat(Files.readAllBytes(root.resolve("video.mkv"))).isEqualTo(first);
    assertThat(retried.toString()).doesNotContain(temp.toString());
  }

  @Test
  void rejectsOutOfOrderHashMismatchedAndOversizeChunks() throws Exception {
    Path root = Files.createDirectories(temp.resolve("shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties(root, DataSize.ofBytes(4), DataSize.ofBytes(1)));
    SharedFolderUploadStatus session = uploads.create(new SharedFolderUploadCreateRequest(
        "", "small.bin", 4, null, null));

    assertConflict(() -> uploads.append(session.id(), 1,
        new ByteArrayInputStream(new byte[] {1}), sha256(new byte[] {1})));
    assertConflict(() -> uploads.append(session.id(), 0,
        new ByteArrayInputStream(new byte[] {1}), sha256(new byte[] {2})));
    assertTooLarge(() -> uploads.append(session.id(), 0,
        new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5}), sha256(new byte[] {1, 2, 3, 4, 5})));
    assertConflict(() -> uploads.append(session.id(), 0,
        new ByteArrayInputStream(new byte[0]), sha256(new byte[0])));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void enabledWindowsUploadUsesNativeStagingResumeFinalizeAndCancel() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-shared"));
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
    boundary.initialize();
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties, boundary);
    byte[] content = "native-content".getBytes();
    try {
      SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
          "", "native.bin", content.length, sha256(content), null));
      uploads.append(upload.id(), 0, new ByteArrayInputStream(content), sha256(content));
      uploads.complete(upload.id(), false);

      SharedFolderUploadStatus cancelled = uploads.create(new SharedFolderUploadCreateRequest(
          "", "cancelled.bin", 1, null, null));
      String cancelledStagingKey = sessions.get(cancelled.id()).getStagingKey();
      uploads.cancel(cancelled.id());

      assertThat(Files.readAllBytes(root.resolve("native.bin"))).isEqualTo(content);
      assertThat(Files.exists(properties.systemRoot()
          .resolve("shared-folder-upload-staging").resolve(cancelledStagingKey))).isFalse();
      assertThat(sessions.get(upload.id()).getState().name()).isEqualTo("COMPLETED");
      assertThat(sessions.get(cancelled.id()).getState().name()).isEqualTo("CANCELLED");
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void failedProgressSaveRollsBackPhysicalAppendSoTheSameChunkCanRetry() throws Exception {
    Path root = Files.createDirectories(temp.resolve("rollback-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    AtomicBoolean failProgressSave = new AtomicBoolean(true);
    SharedFolderUploadSessionRepository repository = mock(SharedFolderUploadSessionRepository.class);
    when(repository.save(any(SharedFolderUploadSession.class))).thenAnswer(invocation -> {
      SharedFolderUploadSession candidate = invocation.getArgument(0);
      if (candidate.getNextOffset() > 0 && failProgressSave.compareAndSet(true, false)) {
        throw new org.springframework.dao.OptimisticLockingFailureException("simulated concurrent save");
      }
      SharedFolderUploadSession saved = copy(candidate);
      stored.put(saved.getId(), saved);
      return copy(saved);
    });
    when(repository.findById(any(String.class))).thenAnswer(invocation ->
        java.util.Optional.ofNullable(stored.get(invocation.getArgument(0))).map(this::copy));
    SharedFolderUploadService uploads = new SharedFolderUploadService(access, repository, properties(root));
    byte[] bytes = "retry-once".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "retry.bin", bytes.length, sha256(bytes), null));

    assertConflict(() -> uploads.append(
        upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes)));
    SharedFolderUploadStatus retried = uploads.append(
        upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
    uploads.complete(upload.id(), false);

    assertThat(retried.nextOffset()).isEqualTo(bytes.length);
    assertThat(Files.readAllBytes(root.resolve("retry.bin"))).isEqualTo(bytes);
  }

  @Test
  void finalizationSaveFailureReconcilesFromTheMovedFileIdentityOnStatus() throws Exception {
    Path root = Files.createDirectories(temp.resolve("finalize-recovery-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    AtomicBoolean failCompletedSave = new AtomicBoolean(true);
    SharedFolderUploadSessionRepository repository = mock(SharedFolderUploadSessionRepository.class);
    when(repository.save(any(SharedFolderUploadSession.class))).thenAnswer(invocation -> {
      SharedFolderUploadSession candidate = invocation.getArgument(0);
      if (candidate.getState().name().equals("COMPLETED")
          && failCompletedSave.compareAndSet(true, false)) {
        throw new org.springframework.dao.OptimisticLockingFailureException("simulated final save");
      }
      SharedFolderUploadSession saved = copy(candidate);
      stored.put(saved.getId(), saved);
      return copy(saved);
    });
    when(repository.findById(any(String.class))).thenAnswer(invocation ->
        java.util.Optional.ofNullable(stored.get(invocation.getArgument(0))).map(this::copy));
    SharedFolderUploadService uploads = new SharedFolderUploadService(access, repository, properties(root));
    byte[] bytes = "recover-finalize".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "recovered.bin", bytes.length, sha256(bytes), null));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));

    assertConflict(() -> uploads.complete(upload.id(), false));
    SharedFolderUploadStatus recovered = uploads.status(upload.id());

    assertThat(recovered.state().name()).isEqualTo("COMPLETED");
    assertThat(Files.readAllBytes(root.resolve("recovered.bin"))).isEqualTo(bytes);
  }

  @Test
  void partialPhysicalAppendFailureTruncatesBackToThePersistedOffset() throws Exception {
    Path root = Files.createDirectories(temp.resolve("partial-rollback-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    AtomicBoolean failPartialWrite = new AtomicBoolean(true);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties(root)) {
      @Override
      protected void appendStagedChunk(Path stagingFile, Path chunk) throws java.io.IOException {
        if (failPartialWrite.compareAndSet(true, false)) {
          byte[] bytes = Files.readAllBytes(chunk);
          Files.write(stagingFile, java.util.Arrays.copyOf(bytes, Math.max(1, bytes.length / 2)),
              java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
          throw new java.io.IOException("simulated mid-append failure");
        }
        super.appendStagedChunk(stagingFile, chunk);
      }
    };
    byte[] bytes = "partial-write".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "partial.bin", bytes.length, sha256(bytes), null));

    assertConflict(() -> uploads.append(
        upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes)));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
    uploads.complete(upload.id(), false);

    assertThat(Files.readAllBytes(root.resolve("partial.bin"))).isEqualTo(bytes);
  }

  @Test
  void cancelSaveFailureReconcilesFromMissingPrivateStagingOnStatus() throws Exception {
    Path root = Files.createDirectories(temp.resolve("cancel-recovery-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    AtomicBoolean failCancelledSave = new AtomicBoolean(true);
    SharedFolderUploadSessionRepository repository = mock(SharedFolderUploadSessionRepository.class);
    when(repository.save(any(SharedFolderUploadSession.class))).thenAnswer(invocation -> {
      SharedFolderUploadSession candidate = invocation.getArgument(0);
      if (candidate.getState().name().equals("CANCELLED")
          && failCancelledSave.compareAndSet(true, false)) {
        throw new org.springframework.dao.OptimisticLockingFailureException("simulated cancel save");
      }
      SharedFolderUploadSession saved = copy(candidate);
      stored.put(saved.getId(), saved);
      return copy(saved);
    });
    when(repository.findById(any(String.class))).thenAnswer(invocation ->
        java.util.Optional.ofNullable(stored.get(invocation.getArgument(0))).map(this::copy));
    SharedFolderUploadService uploads = new SharedFolderUploadService(access, repository, properties(root));
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "cancel.bin", 1, null, null));

    assertConflict(() -> uploads.cancel(upload.id()));
    SharedFolderUploadStatus recovered = uploads.status(upload.id());

    assertThat(recovered.state().name()).isEqualTo("CANCELLED");
  }

  @Test
  void concurrentSameOffsetAppendsSerializeIntoOnePhysicalChunk() throws Exception {
    Path root = Files.createDirectories(temp.resolve("concurrent-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties(root));
    byte[] bytes = "one-chunk".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "concurrent.bin", bytes.length, sha256(bytes), null));
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> {
        start.await();
        return uploads.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
      });
      var second = executor.submit(() -> {
        start.await();
        return uploads.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
      });
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).nextOffset()).isEqualTo(bytes.length);
      assertThat(second.get(10, TimeUnit.SECONDS).nextOffset()).isEqualTo(bytes.length);
    }
    uploads.complete(upload.id(), false);
    assertThat(Files.readAllBytes(root.resolve("concurrent.bin"))).isEqualTo(bytes);
  }

  @Test
  void createMapsConfiguredFileLimitTo413AndPortableReserveRefusalTo507() throws Exception {
    Path root = Files.createDirectories(temp.resolve("limits-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    SharedFolderProperties oversizeProperties = new SharedFolderProperties(
        root, Files.createDirectories(temp.resolve("limits-system")), DataSize.ofBytes(4),
        DataSize.ofBytes(4), DataSize.ofBytes(1), DataSize.ofGigabytes(1),
        Duration.ofDays(1), Duration.ofDays(1), true);
    SharedFolderUploadService oversize = new SharedFolderUploadService(
        access, repository(new ConcurrentHashMap<>()), oversizeProperties);
    assertStatus(413, () -> oversize.create(new SharedFolderUploadCreateRequest(
        "", "large.bin", 5, null, null)));

    SharedFolderProperties reserveProperties = new SharedFolderProperties(
        root, Files.createDirectories(temp.resolve("reserve-system")), DataSize.ofGigabytes(1),
        DataSize.ofBytes(4), DataSize.ofBytes(Long.MAX_VALUE), DataSize.ofGigabytes(1),
        Duration.ofDays(1), Duration.ofDays(1), true);
    SharedFolderUploadService reserve = new SharedFolderUploadService(
        access, repository(new ConcurrentHashMap<>()), reserveProperties);
    assertStatus(507, () -> reserve.create(new SharedFolderUploadCreateRequest(
        "", "reserve.bin", 1, null, null)));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void enabledWindowsReserveRefusalUses507() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-reserve-shared"));
    Path system = Files.createDirectories(temp.resolve("native-reserve-system"));
    SharedFolderProperties properties = new SharedFolderProperties(
        root, system, DataSize.ofGigabytes(1), DataSize.ofBytes(4),
        DataSize.ofBytes(Long.MAX_VALUE), DataSize.ofGigabytes(1),
        Duration.ofDays(1), Duration.ofDays(1), true);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
    boundary.initialize();
    try {
      SharedFolderUploadService uploads = new SharedFolderUploadService(
          access, repository(new ConcurrentHashMap<>()), properties, boundary);
      assertStatus(507, () -> uploads.create(new SharedFolderUploadCreateRequest(
          "", "reserve.bin", 1, null, null)));
    } finally {
      boundary.destroy();
    }
  }

  @SuppressWarnings("unchecked")
  private SharedFolderUploadSessionRepository repository(
      Map<String, SharedFolderUploadSession> sessions) {
    SharedFolderUploadSessionRepository repository = mock(SharedFolderUploadSessionRepository.class);
    when(repository.save(any(SharedFolderUploadSession.class))).thenAnswer(invocation -> {
      SharedFolderUploadSession session = invocation.getArgument(0);
      sessions.put(session.getId(), session);
      return session;
    });
    when(repository.findById(any(String.class))).thenAnswer(invocation ->
        java.util.Optional.ofNullable(sessions.get(invocation.getArgument(0))));
    return repository;
  }

  private SharedFolderProperties properties(Path root) throws Exception {
    return properties(root, DataSize.ofMegabytes(8), DataSize.ofBytes(1));
  }

  private SharedFolderProperties properties(Path root, DataSize chunk, DataSize reserve) throws Exception {
    return new SharedFolderProperties(
        root,
        Files.createDirectories(temp.resolve("system")),
        DataSize.ofGigabytes(10),
        chunk,
        reserve,
        DataSize.ofGigabytes(1),
        Duration.ofDays(30),
        Duration.ofDays(180),
        true);
  }

  private SharedFolderUploadSession copy(SharedFolderUploadSession source) {
    SharedFolderUploadSession copy = new SharedFolderUploadSession();
    copy.setId(source.getId());
    copy.setVersion(source.getVersion());
    copy.setOwnerId(source.getOwnerId());
    copy.setParentPath(source.getParentPath());
    copy.setName(source.getName());
    copy.setExpectedBytes(source.getExpectedBytes());
    copy.setExpectedSha256(source.getExpectedSha256());
    copy.setTargetObservedToken(source.getTargetObservedToken());
    copy.setNextOffset(source.getNextOffset());
    copy.setChunkDigests(new java.util.HashMap<>(source.getChunkDigests()));
    copy.setChunkLengths(new java.util.HashMap<>(source.getChunkLengths()));
    copy.setStagingKey(source.getStagingKey());
    copy.setExpiresAt(source.getExpiresAt());
    copy.setState(source.getState());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setUpdatedAt(source.getUpdatedAt());
    copy.setFinalizingIdentity(source.getFinalizingIdentity());
    copy.setFinalizingReplace(source.getFinalizingReplace());
    return copy;
  }

  private String sha256(byte[] value) throws Exception {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value));
  }

  private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    org.assertj.core.api.Assertions.assertThatThrownBy(action)
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(exception -> assertThat(((org.springframework.web.server.ResponseStatusException) exception)
            .getStatusCode().value()).isEqualTo(409));
  }

  private void assertTooLarge(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    org.assertj.core.api.Assertions.assertThatThrownBy(action)
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(exception -> assertThat(((org.springframework.web.server.ResponseStatusException) exception)
            .getStatusCode().value()).isEqualTo(413));
  }

  private void assertStatus(
      int expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    org.assertj.core.api.Assertions.assertThatThrownBy(action)
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(exception -> assertThat(((org.springframework.web.server.ResponseStatusException) exception)
            .getStatusCode().value()).isEqualTo(expected));
  }
}

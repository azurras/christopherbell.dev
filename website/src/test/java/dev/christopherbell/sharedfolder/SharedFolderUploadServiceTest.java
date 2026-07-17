package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.configuration.filter.RequestSizeLimitFilter;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.fs.WindowsSharedFolderMutationBoundary;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadSessionRepository;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadState;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadStatus;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadCreateRequest;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadFinalizationState;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.sharedfolder.web.SharedFolderWriteController;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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

    assertThat(retried.chunkSizeBytes()).isEqualTo(DataSize.ofMegabytes(8).toBytes());
    assertThat(retried.committedChunks()).containsExactly(
        new dev.christopherbell.sharedfolder.upload.SharedFolderUploadChunkProof(
            0, first.length, sha256(first)));
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
  void unknownLengthOversizedChunkReturns413ThroughFilterControllerAndRealServiceWithoutProgress()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("unknown-length-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties(root, DataSize.ofBytes(4), DataSize.ofBytes(1)));
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "bounded.bin", 8, null, null));
    org.springframework.web.filter.OncePerRequestFilter unknownLength =
        new org.springframework.web.filter.OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              jakarta.servlet.http.HttpServletRequest request,
              jakarta.servlet.http.HttpServletResponse response,
              jakarta.servlet.FilterChain chain) throws jakarta.servlet.ServletException, java.io.IOException {
            chain.doFilter(new jakarta.servlet.http.HttpServletRequestWrapper(request) {
              @Override public int getContentLength() { return -1; }
              @Override public long getContentLengthLong() { return -1; }
            }, response);
          }
        };
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new SharedFolderWriteController(
            mock(SharedFolderMutationService.class), uploads))
        .setControllerAdvice(new ControllerExceptionHandler())
        .addFilters(unknownLength, new RequestSizeLimitFilter(1_000_000, 4))
        .build();

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
            "/api/shared-folder/2026-07-17/uploads/" + upload.id() + "/chunks/0")
        .contentType("application/octet-stream")
        .header("X-Chunk-SHA-256", sha256(new byte[] {1, 2, 3, 4, 5}))
        .content(new byte[] {1, 2, 3, 4, 5})
        .with(request -> {
          request.removeHeader("Content-Length");
          return request;
        }))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
            .isPayloadTooLarge());

    assertThat(uploads.status(upload.id()).nextOffset()).isZero();
    Path staged = properties(root, DataSize.ofBytes(4), DataSize.ofBytes(1)).systemRoot()
        .resolve("shared-folder-upload-staging").resolve(sessions.get(upload.id()).getStagingKey());
    assertThat(Files.notExists(staged) || Files.size(staged) == 0).isTrue();
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
  void conditionalPortableFinalizationPreservesRacerAndOriginalInPrivateQuarantine() throws Exception {
    Path root = Files.createDirectories(temp.resolve("replace-race-upload-shared"));
    Files.writeString(root.resolve("target.bin"), "observed");
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    SharedFolderProperties properties = properties(root);
    String targetToken = new SharedFolderMutationService(access, properties).observedToken("target.bin");
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    AtomicBoolean race = new AtomicBoolean(true);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties) {
      @Override
      protected void moveAtomically(Path source, Path target, boolean replace) throws java.io.IOException {
        if (source.getParent().getFileName().toString().equals("shared-folder-upload-staging")
            && race.compareAndSet(true, false)) {
          Files.writeString(target, "racer");
        }
        super.moveAtomically(source, target, replace);
      }
    };
    byte[] bytes = "replacement".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", bytes.length, sha256(bytes), targetToken));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));

    assertConflict(() -> uploads.complete(upload.id(), true));

    SharedFolderUploadSession pending = sessions.get(upload.id());
    assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("racer");
    assertThat(Files.readString(properties.systemRoot().resolve("shared-folder-upload-quarantine")
        .resolve(pending.getFinalizingQuarantineKey()))).isEqualTo("observed");
    assertThat(uploads.status(upload.id()).state()).isEqualTo(SharedFolderUploadState.FINALIZING);
    assertThat(sessions.get(upload.id()).getFinalizationState())
        .isEqualTo(SharedFolderUploadFinalizationState.RESTORE_PENDING);
  }

  @Test
  void recreatedPortableUploadRestoresObservedTargetAfterCrashFollowingQuarantine() throws Exception {
    Path root = Files.createDirectories(temp.resolve("replace-crash-upload-shared"));
    Files.writeString(root.resolve("target.bin"), "observed");
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    SharedFolderProperties properties = properties(root);
    String targetToken = new SharedFolderMutationService(access, properties).observedToken("target.bin");
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    AtomicBoolean crash = new AtomicBoolean(true);
    SharedFolderUploadService crashing = new SharedFolderUploadService(
        access, repository(sessions), properties) {
      @Override
      protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
        if (state == SharedFolderUploadFinalizationState.TARGET_QUARANTINED
            && crash.compareAndSet(true, false)) {
          throw new AssertionError("simulated process death");
        }
      }
    };
    byte[] bytes = "replacement".getBytes();
    SharedFolderUploadStatus upload = crashing.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", bytes.length, sha256(bytes), targetToken));
    crashing.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashing.complete(upload.id(), true))
        .isInstanceOf(AssertionError.class);

    SharedFolderUploadService recreated = new SharedFolderUploadService(
        access, repository(sessions), properties);
    assertThat(recreated.status(upload.id()).state()).isEqualTo(SharedFolderUploadState.ACTIVE);
    assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("observed");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void nativeConditionalUploadRecoversPostQuarantineAndPostMoveWithGoneQuarantine() throws Exception {
    for (SharedFolderUploadFinalizationState crashPhase : java.util.List.of(
        SharedFolderUploadFinalizationState.PREPARED,
        SharedFolderUploadFinalizationState.TARGET_QUARANTINED,
        SharedFolderUploadFinalizationState.SOURCE_MOVED)) {
      Path root = Files.createDirectories(temp.resolve("native-upload-journal-" + crashPhase));
      Files.writeString(root.resolve("target.bin"), "observed");
      SharedFolderProperties properties = properties(root);
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
      WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
      boundary.initialize();
      try {
        String targetToken = new SharedFolderMutationService(
            access, properties, boundary).observedToken("target.bin");
        AtomicBoolean crash = new AtomicBoolean(true);
        SharedFolderUploadService crashing = new SharedFolderUploadService(
            access, repository(sessions), properties, boundary) {
          @Override
          protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
            if (state == crashPhase && crash.compareAndSet(true, false)) {
              throw new AssertionError("simulated native upload process death at " + state);
            }
          }
        };
        byte[] replacement = "replacement".getBytes();
        SharedFolderUploadStatus upload = crashing.create(new SharedFolderUploadCreateRequest(
            "", "target.bin", replacement.length, sha256(replacement), targetToken));
        crashing.append(upload.id(), 0, new ByteArrayInputStream(replacement), sha256(replacement));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashing.complete(upload.id(), true))
            .isInstanceOf(AssertionError.class);

        SharedFolderUploadSession pending = sessions.get(upload.id());
        if (crashPhase == SharedFolderUploadFinalizationState.SOURCE_MOVED) {
          var quarantined = boundary.quarantineMetadata(pending.getFinalizingQuarantineKey());
          boundary.deleteQuarantine(pending.getFinalizingQuarantineKey(), quarantined);
        }
        SharedFolderUploadService recreated = new SharedFolderUploadService(
            access, repository(sessions), properties, boundary);
        SharedFolderUploadStatus recovered = recreated.status(upload.id());
        if (crashPhase == SharedFolderUploadFinalizationState.PREPARED
            || crashPhase == SharedFolderUploadFinalizationState.TARGET_QUARANTINED) {
          assertThat(recovered.state()).isEqualTo(SharedFolderUploadState.ACTIVE);
          assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("observed");
        } else {
          assertThat(recovered.state()).isEqualTo(SharedFolderUploadState.COMPLETED);
          assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("replacement");
        }
      } finally {
        boundary.destroy();
      }
    }
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
  void recreatedPortableServiceTruncatesExpiredPartialAndFullUncommittedAppendBytes() throws Exception {
    for (String extra : java.util.List.of("x", "uncommitted")) {
      Path root = Files.createDirectories(temp.resolve("restart-portable-" + extra.length()));
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
      SharedFolderProperties properties = properties(root);
      SharedFolderUploadService first = new SharedFolderUploadService(
          access, repository(sessions), properties);
      byte[] committed = "good".getBytes();
      byte[] remaining = "rest".getBytes();
      SharedFolderUploadStatus upload = first.create(new SharedFolderUploadCreateRequest(
          "", "restart.bin", committed.length + remaining.length, null, null));
      first.append(upload.id(), 0, new ByteArrayInputStream(committed), sha256(committed));
      SharedFolderUploadSession stored = sessions.get(upload.id());
      Path staging = properties.systemRoot().resolve("shared-folder-upload-staging")
          .resolve(stored.getStagingKey());
      Files.writeString(staging, extra, java.nio.file.StandardOpenOption.APPEND);
      stored.setState(SharedFolderUploadState.APPENDING);
      stored.setAppendLeaseToken("dead-instance:request");
      stored.setAppendLeaseExpiresAt(Instant.now().minusSeconds(1));
      stored.setAppendOffset((long) committed.length);
      stored.setAppendLength((long) extra.length());
      stored.setAppendDigest(sha256(extra.getBytes()));

      SharedFolderUploadService recreated = new SharedFolderUploadService(
          access, repository(sessions), properties);
      recreated.append(upload.id(), committed.length,
          new ByteArrayInputStream(remaining), sha256(remaining));
      recreated.complete(upload.id(), false);

      assertThat(Files.readAllBytes(root.resolve("restart.bin")))
          .isEqualTo("goodrest".getBytes());
    }
  }

  @Test
  void competingInstanceCannotStealUnexpiredAppendLeaseOrTruncateInflightBytes() throws Exception {
    Path root = Files.createDirectories(temp.resolve("live-lease-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderProperties properties = properties(root);
    SharedFolderUploadService first = new SharedFolderUploadService(
        access, repository(sessions), properties);
    byte[] committed = "good".getBytes();
    SharedFolderUploadStatus upload = first.create(new SharedFolderUploadCreateRequest(
        "", "lease.bin", 8, null, null));
    first.append(upload.id(), 0, new ByteArrayInputStream(committed), sha256(committed));
    SharedFolderUploadSession stored = sessions.get(upload.id());
    Path staging = properties.systemRoot().resolve("shared-folder-upload-staging")
        .resolve(stored.getStagingKey());
    Files.writeString(staging, "live", java.nio.file.StandardOpenOption.APPEND);
    stored.setState(SharedFolderUploadState.APPENDING);
    stored.setAppendLeaseToken("live-instance:request");
    stored.setAppendLeaseExpiresAt(Instant.now().plusSeconds(60));
    stored.setAppendOffset((long) committed.length);
    stored.setAppendLength(4L);
    stored.setAppendDigest(sha256("live".getBytes()));
    SharedFolderUploadService competing = new SharedFolderUploadService(
        access, repository(sessions), properties);

    assertConflict(() -> competing.append(upload.id(), committed.length,
        new ByteArrayInputStream("rest".getBytes()), sha256("rest".getBytes())));

    assertThat(Files.readString(staging)).isEqualTo("goodlive");
    assertThat(sessions.get(upload.id()).getAppendLeaseToken()).isEqualTo("live-instance:request");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void recreatedNativeServiceTruncatesExpiredPartialAndFullUncommittedAppendBytes() throws Exception {
    for (String extra : java.util.List.of("x", "uncommitted")) {
      Path root = Files.createDirectories(temp.resolve("restart-native-" + extra.length()));
      SharedFolderProperties properties = properties(root);
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
      WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
      boundary.initialize();
      try {
        SharedFolderUploadService first = new SharedFolderUploadService(
            access, repository(sessions), properties, boundary);
        byte[] committed = "good".getBytes();
        byte[] remaining = "rest".getBytes();
        SharedFolderUploadStatus upload = first.create(new SharedFolderUploadCreateRequest(
            "", "native-restart-" + extra.length() + ".bin",
            committed.length + remaining.length, null, null));
        first.append(upload.id(), 0, new ByteArrayInputStream(committed), sha256(committed));
        SharedFolderUploadSession stored = sessions.get(upload.id());
        Path staging = properties.systemRoot().resolve("shared-folder-upload-staging")
            .resolve(stored.getStagingKey());
        Files.writeString(staging, extra, java.nio.file.StandardOpenOption.APPEND);
        stored.setState(SharedFolderUploadState.APPENDING);
        stored.setAppendLeaseToken("dead-native:request");
        stored.setAppendLeaseExpiresAt(Instant.now().minusSeconds(1));
        stored.setAppendOffset((long) committed.length);
        stored.setAppendLength((long) extra.length());
        stored.setAppendDigest(sha256(extra.getBytes()));

        SharedFolderUploadService recreated = new SharedFolderUploadService(
            access, repository(sessions), properties, boundary);
        recreated.append(upload.id(), committed.length,
            new ByteArrayInputStream(remaining), sha256(remaining));
        recreated.complete(upload.id(), false);

        assertThat(Files.readAllBytes(root.resolve("native-restart-" + extra.length() + ".bin")))
            .isEqualTo("goodrest".getBytes());
      } finally {
        boundary.destroy();
      }
    }
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
  void directCreateRejectsNonPositiveSizeBeforeRepositoryOrFilesystemMutation() throws Exception {
    Path root = Files.createDirectories(temp.resolve("validation-upload-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    SharedFolderUploadSessionRepository repository = mock(SharedFolderUploadSessionRepository.class);
    SharedFolderUploadService uploads = new SharedFolderUploadService(access, repository, properties(root));

    assertStatus(400, () -> uploads.create(new SharedFolderUploadCreateRequest(
        "", "zero.bin", 0, null, null)));
    assertStatus(400, () -> uploads.create(new SharedFolderUploadCreateRequest(
        "", "negative.bin", -1, null, null)));

    verify(repository, org.mockito.Mockito.never()).save(any());
    assertThat(Files.list(properties(root).systemRoot()).findAny()).isEmpty();
  }

  @Test
  void uploadStatusContractUsesExact400404409And503AcrossPortableAndNativeModes() throws Exception {
    Path root = Files.createDirectories(temp.resolve("upload-status-contract"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    for (boolean nativeMode : java.util.List.of(false, true)) {
      Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
      WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
      when(boundary.nativeMode()).thenReturn(nativeMode);
      SharedFolderUploadService uploads = new SharedFolderUploadService(
          access, repository(stored), properties(root), boundary);
      assertStatus(400, () -> uploads.status(""));
      assertStatus(404, () -> uploads.status("missing"));
      SharedFolderUploadSession incomplete = activeSession(
          "incomplete-" + nativeMode, account.getId(), "staging-key");
      stored.put(incomplete.getId(), incomplete);
      assertStatus(409, () -> uploads.complete(incomplete.getId(), false));

      SharedFolderProperties enabled = properties(root);
      SharedFolderProperties disabled = new SharedFolderProperties(
          enabled.root(), enabled.systemRoot(), enabled.maxUpload(), enabled.uploadChunk(),
          enabled.minimumFreeSpace(), enabled.transcodeCacheLimit(), enabled.recycleRetention(),
          enabled.auditRetention(), false);
      SharedFolderUploadService unavailable = new SharedFolderUploadService(
          access, repository(new ConcurrentHashMap<>()), disabled, boundary);
      assertStatus(503, () -> unavailable.create(new SharedFolderUploadCreateRequest(
          "", "file.bin", 1, null, null)));
    }
  }

  @Test
  void statusExpiresActiveSessionAndCompleteNeverFinalizesExpiredBytes() throws Exception {
    Path root = Files.createDirectories(temp.resolve("expired-upload-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties(root));
    byte[] bytes = "expired".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "expired.bin", bytes.length, sha256(bytes), null));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
    sessions.get(upload.id()).setExpiresAt(Instant.now().minusSeconds(1));

    assertThat(uploads.status(upload.id()).state()).isEqualTo(SharedFolderUploadState.EXPIRED);
    assertConflict(() -> uploads.complete(upload.id(), false));
    assertThat(Files.exists(root.resolve("expired.bin"))).isFalse();
  }

  @Test
  void nativeCancelRemainsPendingUntilDeletionIsConfirmedAndStatusRetriesRecovery() throws Exception {
    Path root = Files.createDirectories(temp.resolve("cancel-native-failure-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(sessions);
    SharedFolderUploadSession session = activeSession("cancel-native", account.getId(), "staging-key");
    sessions.put(session.getId(), session);
    WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
    when(boundary.nativeMode()).thenReturn(true);
    when(boundary.deleteStagingIfExists("staging-key"))
        .thenThrow(new dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException(
            "sharing conflict", 0xC0000043))
        .thenReturn(true);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository, properties(root), boundary);

    assertConflict(() -> uploads.cancel(session.getId()));
    assertThat(sessions.get(session.getId()).getState())
        .isEqualTo(SharedFolderUploadState.CANCEL_PENDING);
    assertThat(uploads.status(session.getId()).state()).isEqualTo(SharedFolderUploadState.CANCELLED);
    verify(boundary, org.mockito.Mockito.times(2)).deleteStagingIfExists("staging-key");
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
      synchronized (sessions) {
        SharedFolderUploadSession candidate = invocation.getArgument(0);
        SharedFolderUploadSession existing = sessions.get(candidate.getId());
        if (existing != null && !java.util.Objects.equals(
            existing.getVersion(), candidate.getVersion())) {
          throw new org.springframework.dao.OptimisticLockingFailureException("stale upload session");
        }
        SharedFolderUploadSession saved = copy(candidate);
        saved.setVersion(existing == null ? 0L : existing.getVersion() + 1L);
        sessions.put(saved.getId(), saved);
        return copy(saved);
      }
    });
    when(repository.findById(any(String.class))).thenAnswer(invocation ->
        java.util.Optional.ofNullable(sessions.get(invocation.getArgument(0))).map(this::copy));
    return repository;
  }

  private SharedFolderUploadSession activeSession(String id, String ownerId, String stagingKey) {
    SharedFolderUploadSession session = new SharedFolderUploadSession();
    session.setId(id);
    session.setVersion(0L);
    session.setOwnerId(ownerId);
    session.setParentPath("");
    session.setName("cancel.bin");
    session.setExpectedBytes(1);
    session.setNextOffset(0);
    session.setStagingKey(stagingKey);
    session.setState(SharedFolderUploadState.ACTIVE);
    session.setCreatedAt(Instant.now());
    session.setUpdatedAt(Instant.now());
    session.setExpiresAt(Instant.now().plusSeconds(60));
    return session;
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
    copy.setAppendLeaseToken(source.getAppendLeaseToken());
    copy.setAppendLeaseExpiresAt(source.getAppendLeaseExpiresAt());
    copy.setAppendOffset(source.getAppendOffset());
    copy.setAppendLength(source.getAppendLength());
    copy.setAppendDigest(source.getAppendDigest());
    copy.setAppendChunkKey(source.getAppendChunkKey());
    copy.setExpiresAt(source.getExpiresAt());
    copy.setState(source.getState());
    copy.setCreatedAt(source.getCreatedAt());
    copy.setUpdatedAt(source.getUpdatedAt());
    copy.setFinalizingIdentity(source.getFinalizingIdentity());
    copy.setFinalizingReplace(source.getFinalizingReplace());
    copy.setFinalizingTargetIdentity(source.getFinalizingTargetIdentity());
    copy.setFinalizingQuarantineKey(source.getFinalizingQuarantineKey());
    copy.setFinalizationState(source.getFinalizationState());
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

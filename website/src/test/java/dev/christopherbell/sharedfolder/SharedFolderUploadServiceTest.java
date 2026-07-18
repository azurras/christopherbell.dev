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
  void replacementCompletionStopsBeforeQuarantineWhenTheFencedScanLosesItsLease()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("lost-finalizer-lease"));
    Files.write(root.resolve("target.bin"), new byte[256 * 1024]);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(stored);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository, properties(root));
    byte[] content = "replacement".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", content.length, sha256(content),
        new SharedFolderMutationService(access, properties(root)).observedToken("target.bin")));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(content), sha256(content));
    org.mockito.Mockito.doReturn(0L).when(repository)
        .renewFinalizationLease(any(), any(), any(), any(), any());

    assertConflict(() -> uploads.complete(upload.id(), true));

    verify(repository, org.mockito.Mockito.atLeastOnce())
        .renewFinalizationLease(any(), any(), any(), any(), any());
    assertThat(Files.size(root.resolve("target.bin"))).isEqualTo(256 * 1024);
    assertThat(Files.exists(properties(root).systemRoot().resolve("shared-folder-upload-staging")
        .resolve(stored.get(upload.id()).getStagingKey()))).isTrue();
    try (var quarantined = Files.list(properties(root).systemRoot()
        .resolve("shared-folder-upload-quarantine"))) {
      assertThat(quarantined).isEmpty();
    }
  }

  @Test
  void slowSubMegabyteUploadScanRenewsByTimeBeforeTheOriginalShortLeaseExpires()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("timed-finalizer-heartbeat"));
    Files.write(root.resolve("target.bin"), new byte[32 * 1024]);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(stored);
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-07-17T12:00:00Z"));
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository, properties(root)) {
      @Override protected Instant leaseNow() {
        return clock.getAndUpdate(value -> value.plusMillis(40));
      }
      @Override protected Duration finalizationLeaseDuration() { return Duration.ofMillis(100); }
    };
    byte[] content = "replacement".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", content.length, sha256(content),
        new SharedFolderMutationService(access, properties(root)).observedToken("target.bin")));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(content), sha256(content));

    uploads.complete(upload.id(), true);

    verify(repository, org.mockito.Mockito.atLeast(4))
        .renewFinalizationLease(any(), any(), any(), any(), any());
    assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("replacement");
  }

  @Test
  void expiredUploadRecoveryStopsWhenItsRecoveryTokenIsLostDuringIdentityScan()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("lost-upload-recovery-lease"));
    Files.write(root.resolve("target.bin"), new byte[256 * 1024]);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(stored);
    SharedFolderUploadService crashing = new SharedFolderUploadService(
        access, repository, properties(root)) {
      @Override
      protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
        if (state == SharedFolderUploadFinalizationState.TARGET_QUARANTINED) {
          throw new AssertionError("simulated process death");
        }
      }
    };
    byte[] content = "replacement".getBytes();
    SharedFolderUploadStatus upload = crashing.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", content.length, sha256(content),
        new SharedFolderMutationService(access, properties(root)).observedToken("target.bin")));
    crashing.append(upload.id(), 0, new ByteArrayInputStream(content), sha256(content));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> crashing.complete(upload.id(), true))
        .isInstanceOf(AssertionError.class);
    SharedFolderUploadSession durable = stored.get(upload.id());
    durable.setFinalizationLeaseExpiresAt(Instant.EPOCH);
    org.mockito.Mockito.doReturn(0L).when(repository)
        .renewFinalizationLease(any(), any(), any(), any(), any());

    SharedFolderUploadService recovering = new SharedFolderUploadService(
        access, repository, properties(root));
    assertConflict(() -> recovering.status(upload.id()));

    assertThat(Files.notExists(root.resolve("target.bin"))).isTrue();
    assertThat(Files.exists(properties(root).systemRoot().resolve("shared-folder-upload-staging")
        .resolve(durable.getStagingKey()))).isTrue();
    assertThat(Files.size(properties(root).systemRoot().resolve("shared-folder-upload-quarantine")
        .resolve(durable.getFinalizingQuarantineKey()))).isEqualTo(256 * 1024);
    assertThat(stored.get(upload.id()).getFinalizationState())
        .isEqualTo(SharedFolderUploadFinalizationState.PREPARED);
  }

  @Test
  void appendWriterStopsBeforeChangingStagingWhenItsFencedLeaseIsLost() throws Exception {
    Path root = Files.createDirectories(temp.resolve("lost-append-writer-lease"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(stored);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository, properties(root));
    byte[] content = "chunk".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", content.length, sha256(content), null));
    org.mockito.Mockito.doReturn(0L).when(repository)
        .renewAppendLease(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());

    assertConflict(() -> uploads.append(
        upload.id(), 0, new ByteArrayInputStream(content), sha256(content)));

    verify(repository, org.mockito.Mockito.atLeastOnce())
        .renewAppendLease(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    Path staging = properties(root).systemRoot().resolve("shared-folder-upload-staging")
        .resolve(stored.get(upload.id()).getStagingKey());
    assertThat(Files.notExists(staging) || Files.size(staging) == 0L).isTrue();
    assertThat(stored.get(upload.id()).getNextOffset()).isZero();
    assertThat(stored.get(upload.id()).getChunkDigests()).isEmpty();
    assertThat(stored.get(upload.id()).getChunkLengths()).isEmpty();
  }

  @Test
  void appendWriterRenewsAfterABlockingReadAndBeforeItsFirstPhysicalWrite() throws Exception {
    Path root = Files.createDirectories(temp.resolve("pre-write-append-heartbeat"));
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(stored);
    AtomicBoolean loseLeaseBeforeWrite = new AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<Instant> clock =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-07-18T12:00:00Z"));
    org.mockito.Mockito.doAnswer(invocation -> loseLeaseBeforeWrite.get() ? 0L : 1L)
        .when(repository).renewAppendLease(
            any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository, properties) {
      @Override
      protected void beforeAppendPhysicalWrite() {
        loseLeaseBeforeWrite.set(true);
        clock.updateAndGet(now -> now.plusMillis(20));
      }
      @Override protected Instant leaseNow() { return clock.get(); }
      @Override protected Duration appendLeaseDuration() { return Duration.ofMillis(30); }
    };
    byte[] content = "chunk".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", content.length, sha256(content), null));

    assertConflict(() -> uploads.append(
        upload.id(), 0, new ByteArrayInputStream(content), sha256(content)));

    Path staging = properties.systemRoot().resolve("shared-folder-upload-staging")
        .resolve(stored.get(upload.id()).getStagingKey());
    assertThat(Files.size(staging)).isZero();
    assertThat(stored.get(upload.id()).getNextOffset()).isZero();
  }

  @Test
  void onlyOneOfTwoStaleReconcilersCanClaimAnExpiredAppendBeforeTruncation()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("two-stale-append-reconcilers"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(stored);
    SharedFolderProperties sharedProperties = properties(root);
    SharedFolderUploadService setup = new SharedFolderUploadService(
        access, repository, sharedProperties);
    SharedFolderUploadStatus upload = setup.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", 8, null, null));
    SharedFolderUploadSession stale = stored.get(upload.id());
    stale.setState(SharedFolderUploadState.APPENDING);
    stale.setAppendLeaseToken("expired-writer");
    stale.setAppendLeaseExpiresAt(Instant.EPOCH);
    stale.setAppendOffset(0L);
    stale.setAppendLength(8L);
    stale.setAppendDigest(sha256("unowned!".getBytes()));
    Path staging = sharedProperties.systemRoot().resolve("shared-folder-upload-staging")
        .resolve(stale.getStagingKey());
    Files.writeString(staging, "unowned!");
    CountDownLatch bothLoadedStaleLease = new CountDownLatch(2);
    java.util.concurrent.atomic.AtomicInteger physicalTransitions =
        new java.util.concurrent.atomic.AtomicInteger();
    class RacingRecoveryService extends SharedFolderUploadService {
      RacingRecoveryService() { super(access, repository, sharedProperties); }
      @Override protected void beforeExpiredAppendLeaseClaim() {
        bothLoadedStaleLease.countDown();
        try {
          if (!bothLoadedStaleLease.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("reconcilers did not overlap");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new AssertionError(exception);
        }
      }
      @Override protected void beforeExpiredAppendPhysicalTransition() {
        physicalTransitions.incrementAndGet();
      }
    }
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> new RacingRecoveryService().status(upload.id()));
      var second = executor.submit(() -> new RacingRecoveryService().status(upload.id()));
      assertThat(first.get(10, TimeUnit.SECONDS).state())
          .isIn(SharedFolderUploadState.ACTIVE, SharedFolderUploadState.APPENDING);
      assertThat(second.get(10, TimeUnit.SECONDS).state())
          .isIn(SharedFolderUploadState.ACTIVE, SharedFolderUploadState.APPENDING);
    } finally {
      executor.shutdownNow();
    }

    verify(repository, org.mockito.Mockito.times(2))
        .claimExpiredAppendLease(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
            any(), any(), any(), any());
    assertThat(physicalTransitions).hasValue(1);
    assertThat(Files.size(staging)).isZero();
    assertThat(stored.get(upload.id()).getState()).isEqualTo(SharedFolderUploadState.ACTIVE);
  }

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
    stubFinalizationRenewal(repository, stored);
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
    stubFinalizationRenewal(repository, stored);
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
      protected void beforePortablePrivateMoveOut(
          String directory, String key, Path target) throws java.io.IOException {
        if (directory.equals("shared-folder-upload-staging")
            && race.compareAndSet(true, false)) {
          Files.writeString(target, "racer");
        }
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
  void durableUploadReplacementRejectsNonEmptyDirectoryBeforeDisplacement() throws Exception {
    Path root = Files.createDirectories(temp.resolve("upload-nonempty-target"));
    Path target = Files.createDirectories(root.resolve("target"));
    Files.writeString(target.resolve("child.txt"), "child");
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    String token = new SharedFolderMutationService(access, properties).observedToken("target");
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties);
    byte[] replacement = "new".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target", replacement.length, sha256(replacement), token));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(replacement), sha256(replacement));

    assertConflict(() -> uploads.complete(upload.id(), true));

    assertThat(Files.readString(target.resolve("child.txt"))).isEqualTo("child");
    assertThat(Files.exists(properties.systemRoot().resolve("shared-folder-upload-staging")
        .resolve(sessions.get(upload.id()).getStagingKey()))).isTrue();
  }

  @Test
  void durableUploadReplacementRechecksQuarantinedDirectoryBeforeMovingStaging() throws Exception {
    Path root = Files.createDirectories(temp.resolve("upload-directory-recheck"));
    Files.createDirectories(root.resolve("target"));
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    String token = new SharedFolderMutationService(access, properties).observedToken("target");
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(sessions), properties) {
      @Override
      protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
        if (state == SharedFolderUploadFinalizationState.TARGET_QUARANTINED) {
          try {
            String key = sessions.values().iterator().next().getFinalizingQuarantineKey();
            Files.writeString(properties.systemRoot().resolve("shared-folder-upload-quarantine")
                .resolve(key).resolve("late-child.txt"), "late");
          } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
          }
        }
      }
    };
    byte[] replacement = "new".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target", replacement.length, sha256(replacement), token));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(replacement), sha256(replacement));

    assertConflict(() -> uploads.complete(upload.id(), true));

    assertThat(Files.readString(root.resolve("target/late-child.txt"))).isEqualTo("late");
    assertThat(Files.exists(properties.systemRoot().resolve("shared-folder-upload-staging")
        .resolve(sessions.get(upload.id()).getStagingKey()))).isTrue();
  }

  @Test
  void durableUploadReplacementDetectsSameSizeTargetEditAndDisappearanceBeforeDisplacement()
      throws Exception {
    for (boolean disappear : java.util.List.of(false, true)) {
      Path root = Files.createDirectories(temp.resolve("upload-target-change-" + disappear));
      Files.writeString(root.resolve("target.bin"), "AAAA");
      SharedFolderProperties properties = properties(root);
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      String token = new SharedFolderMutationService(access, properties).observedToken("target.bin");
      Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
      AtomicBoolean changed = new AtomicBoolean();
      SharedFolderUploadService uploads = new SharedFolderUploadService(
          access, repository(sessions), properties) {
        @Override
        protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
          if (state == SharedFolderUploadFinalizationState.PREPARED
              && changed.compareAndSet(false, true)) {
            try {
              if (disappear) Files.delete(root.resolve("target.bin"));
              else Files.writeString(root.resolve("target.bin"), "BBBB");
            } catch (java.io.IOException exception) {
              throw new AssertionError(exception);
            }
          }
        }
      };
      byte[] replacement = "new".getBytes();
      SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
          "", "target.bin", replacement.length, sha256(replacement), token));
      uploads.append(upload.id(), 0, new ByteArrayInputStream(replacement), sha256(replacement));

      assertConflict(() -> uploads.complete(upload.id(), true));

      Path staging = properties.systemRoot().resolve("shared-folder-upload-staging")
          .resolve(sessions.get(upload.id()).getStagingKey());
      assertThat(Files.exists(staging)).isTrue();
      if (disappear) assertThat(Files.notExists(root.resolve("target.bin"))).isTrue();
      else assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("BBBB");
    }
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
    sessions.get(upload.id()).setFinalizationLeaseExpiresAt(Instant.now().minusSeconds(1));

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
        pending.setFinalizationLeaseExpiresAt(Instant.now().minusSeconds(1));
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
  @EnabledOnOs(OS.WINDOWS)
  void nativeUploadReplacementTargetDisappearanceConflictsWithStagingIntact() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-upload-disappeared-target"));
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
      String targetToken = new SharedFolderMutationService(access, properties, boundary)
          .observedToken("target.bin");
      AtomicBoolean removeTarget = new AtomicBoolean(true);
      SharedFolderUploadService uploads = new SharedFolderUploadService(
          access, repository(sessions), properties, boundary) {
        @Override
        protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
          if (state == SharedFolderUploadFinalizationState.PREPARED
              && removeTarget.compareAndSet(true, false)) {
            try {
              Files.delete(root.resolve("target.bin"));
            } catch (java.io.IOException exception) {
              throw new AssertionError(exception);
            }
          }
        }
      };
      byte[] replacement = "replacement".getBytes();
      SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
          "", "target.bin", replacement.length, sha256(replacement), targetToken));
      uploads.append(upload.id(), 0, new ByteArrayInputStream(replacement), sha256(replacement));
      String stagingKey = sessions.get(upload.id()).getStagingKey();

      assertConflict(() -> uploads.complete(upload.id(), true));

      assertThat(Files.readAllBytes(properties.systemRoot()
          .resolve("shared-folder-upload-staging").resolve(stagingKey))).isEqualTo(replacement);
      assertThat(Files.notExists(root.resolve("target.bin"))).isTrue();
      assertThat(sessions.get(upload.id()).getState()).isEqualTo(SharedFolderUploadState.ACTIVE);
      assertThat(sessions.get(upload.id()).getFinalizationState()).isNull();
    } finally {
      boundary.destroy();
    }
  }

  @Test
  void livePortableFinalizationIsInvisibleToStatusAndCompetingComplete() throws Exception {
    for (boolean replace : java.util.List.of(false, true)) {
      for (SharedFolderUploadFinalizationState phase : replace
          ? java.util.List.of(SharedFolderUploadFinalizationState.PREPARED,
              SharedFolderUploadFinalizationState.TARGET_QUARANTINED)
          : java.util.List.of(SharedFolderUploadFinalizationState.PREPARED)) {
        Path root = Files.createDirectories(temp.resolve("live-finalize-" + replace + "-" + phase));
        if (replace) Files.writeString(root.resolve("target.bin"), "old");
        SharedFolderProperties properties = properties(root);
        Account account = new Account();
        account.setId("account-1");
        SharedFolderAccessService access = mock(SharedFolderAccessService.class);
        when(access.requireWrite()).thenReturn(account);
        Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SharedFolderUploadSessionRepository repository = repository(sessions);
        SharedFolderUploadService writer = new SharedFolderUploadService(
            access, repository, properties) {
          @Override
          protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
            if (state == phase) {
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
        String targetToken = replace
            ? new SharedFolderMutationService(access, properties).observedToken("target.bin") : null;
        byte[] replacement = "new".getBytes();
        SharedFolderUploadStatus upload = writer.create(new SharedFolderUploadCreateRequest(
            "", "target.bin", replacement.length, sha256(replacement), targetToken));
        writer.append(upload.id(), 0, new ByteArrayInputStream(replacement), sha256(replacement));
        try (var executor = Executors.newSingleThreadExecutor()) {
          var active = executor.submit(() -> writer.complete(upload.id(), replace));
          assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
          SharedFolderUploadSession live = sessions.get(upload.id());
          assertThat(live.getFinalizationLeaseToken()).isNotBlank();
          assertThat(live.getFinalizationLeaseExpiresAt()).isAfter(Instant.now());
          SharedFolderUploadService competing = new SharedFolderUploadService(
              access, repository, properties);

          assertThat(competing.status(upload.id()).state()).isEqualTo(SharedFolderUploadState.FINALIZING);
          assertConflict(() -> competing.complete(upload.id(), replace));
          assertThat(sessions.get(upload.id()).getFinalizationLeaseToken())
              .isEqualTo(live.getFinalizationLeaseToken());

          release.countDown();
          try {
            assertThat(active.get(10, TimeUnit.SECONDS).state())
                .isEqualTo(SharedFolderUploadState.COMPLETED);
          } catch (java.util.concurrent.ExecutionException exception) {
            throw new AssertionError("active portable finalization failed at " + phase, exception);
          }
        }
        assertThat(Files.readString(root.resolve("target.bin"))).isEqualTo("new");
      }
    }
  }

  @Test
  void renewedFinalizationLeaseCannotBeStolenByAStaleExpiredStatusClaim() throws Exception {
    Path root = Files.createDirectories(temp.resolve("renew-before-finalization-claim"));
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    SharedFolderUploadSessionRepository repository = repository(sessions);
    SharedFolderUploadService uploads = new SharedFolderUploadService(access, repository, properties);
    byte[] bytes = "content".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", bytes.length, sha256(bytes), null));
    uploads.append(upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
    SharedFolderUploadSession stored = sessions.get(upload.id());
    stored.setState(SharedFolderUploadState.FINALIZING);
    stored.setFinalizingIdentity("expected-staging-identity");
    stored.setFinalizingReplace(false);
    stored.setFinalizationState(SharedFolderUploadFinalizationState.PREPARED);
    stored.setFinalizationLeaseToken("writer-token");
    stored.setFinalizationLeaseExpiresAt(Instant.EPOCH);
    Instant renewedUntil = Instant.now().plusSeconds(300);
    org.mockito.Mockito.doAnswer(invocation -> {
      SharedFolderUploadSession current = sessions.get(invocation.getArgument(0));
      current.setFinalizationLeaseExpiresAt(renewedUntil);
      current.setUpdatedAt(Instant.now());
      return 0L;
    }).when(repository).claimExpiredFinalizationLease(
        any(), any(), any(), any(), any(), any(), any());

    assertThat(uploads.status(upload.id()).state()).isEqualTo(SharedFolderUploadState.FINALIZING);

    assertThat(sessions.get(upload.id()).getFinalizationLeaseToken()).isEqualTo("writer-token");
    assertThat(sessions.get(upload.id()).getFinalizationLeaseExpiresAt()).isEqualTo(renewedUntil);
    assertThat(Files.notExists(root.resolve("target.bin"))).isTrue();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void liveNativeReplacementFinalizationIsNotReconciledByAnotherService() throws Exception {
    for (SharedFolderUploadFinalizationState phase : java.util.List.of(
        SharedFolderUploadFinalizationState.PREPARED,
        SharedFolderUploadFinalizationState.TARGET_QUARANTINED)) {
      Path root = Files.createDirectories(temp.resolve("live-native-finalize-" + phase));
      Files.writeString(root.resolve("target.bin"), "old");
      SharedFolderProperties properties = properties(root);
      Account account = new Account();
      account.setId("account-1");
      SharedFolderAccessService access = mock(SharedFolderAccessService.class);
      when(access.requireWrite()).thenReturn(account);
      Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
      SharedFolderUploadSessionRepository repository = repository(sessions);
      WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
      boundary.initialize();
      try {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SharedFolderUploadService writer = new SharedFolderUploadService(
            access, repository, properties, boundary) {
          @Override
          protected void afterPhysicalFinalizationTransition(SharedFolderUploadFinalizationState state) {
            if (state == phase) {
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
        String token = new SharedFolderMutationService(access, properties, boundary)
            .observedToken("target.bin");
        byte[] replacement = "new".getBytes();
        SharedFolderUploadStatus upload = writer.create(new SharedFolderUploadCreateRequest(
            "", "target.bin", replacement.length, sha256(replacement), token));
        writer.append(upload.id(), 0, new ByteArrayInputStream(replacement), sha256(replacement));
        try (var executor = Executors.newSingleThreadExecutor()) {
          var active = executor.submit(() -> writer.complete(upload.id(), true));
          assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
          SharedFolderUploadSession live = sessions.get(upload.id());
          assertThat(live.getFinalizationLeaseToken()).isNotBlank();
          SharedFolderUploadService competing = new SharedFolderUploadService(
              access, repository, properties, boundary);
          assertThat(competing.status(upload.id()).state()).isEqualTo(SharedFolderUploadState.FINALIZING);
          assertConflict(() -> competing.complete(upload.id(), true));
          release.countDown();
          try {
            assertThat(active.get(10, TimeUnit.SECONDS).state())
                .isEqualTo(SharedFolderUploadState.COMPLETED);
          } catch (java.util.concurrent.ExecutionException exception) {
            Throwable rootCause = exception;
            while (rootCause.getCause() != null) rootCause = rootCause.getCause();
            String nativeStatus = rootCause instanceof
                dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge
                    .NativeBoundaryException nativeFailure
                ? " status=0x" + Integer.toHexString(nativeFailure.ntStatus()) : "";
            throw new AssertionError(
                "active native finalization failed at " + phase + nativeStatus, exception);
          }
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
      protected void appendStagedChunk(
          SharedFolderUploadSession session, java.nio.channels.FileChannel stagingFile,
          java.nio.channels.FileChannel chunk) throws java.io.IOException {
        if (failPartialWrite.compareAndSet(true, false)) {
          byte[] bytes = new byte[Math.toIntExact(chunk.size())];
          chunk.position(0);
          chunk.read(java.nio.ByteBuffer.wrap(bytes));
          byte[] partial = java.util.Arrays.copyOf(bytes, Math.max(1, bytes.length / 2));
          stagingFile.position(stagingFile.size());
          stagingFile.write(java.nio.ByteBuffer.wrap(partial));
          stagingFile.force(true);
          throw new java.io.IOException("simulated mid-append failure");
        }
        super.appendStagedChunk(session, stagingFile, chunk);
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
  void portableAppendReloadsCommittedProgressWhenFinalSaveCommitsThenThrows() throws Exception {
    Path root = Files.createDirectories(temp.resolve("ambiguous-portable-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    AtomicBoolean throwAfterCommit = new AtomicBoolean(false);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, commitThenThrowRepository(sessions, throwAfterCommit), properties(root));
    byte[] bytes = "committed-on-error".getBytes();
    SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
        "", "ambiguous.bin", bytes.length, sha256(bytes), null));

    throwAfterCommit.set(true);
    SharedFolderUploadStatus appended = uploads.append(
        upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
    SharedFolderUploadStatus retried = uploads.append(
        upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
    uploads.complete(upload.id(), false);

    assertThat(appended.nextOffset()).isEqualTo(bytes.length);
    assertThat(retried.nextOffset()).isEqualTo(bytes.length);
    assertThat(Files.readAllBytes(root.resolve("ambiguous.bin"))).isEqualTo(bytes);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void nativeAppendReloadsCommittedProgressWhenFinalSaveCommitsThenThrows() throws Exception {
    Path root = Files.createDirectories(temp.resolve("ambiguous-native-shared"));
    SharedFolderProperties properties = properties(root);
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> sessions = new ConcurrentHashMap<>();
    AtomicBoolean throwAfterCommit = new AtomicBoolean(false);
    WindowsSharedFolderMutationBoundary boundary = new WindowsSharedFolderMutationBoundary(properties);
    boundary.initialize();
    try {
      SharedFolderUploadService uploads = new SharedFolderUploadService(
          access, commitThenThrowRepository(sessions, throwAfterCommit), properties, boundary);
      byte[] bytes = "native-committed-on-error".getBytes();
      SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
          "", "native-ambiguous.bin", bytes.length, sha256(bytes), null));

      throwAfterCommit.set(true);
      SharedFolderUploadStatus appended = uploads.append(
          upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
      SharedFolderUploadStatus retried = uploads.append(
          upload.id(), 0, new ByteArrayInputStream(bytes), sha256(bytes));
      uploads.complete(upload.id(), false);

      assertThat(appended.nextOffset()).isEqualTo(bytes.length);
      assertThat(retried.nextOffset()).isEqualTo(bytes.length);
      assertThat(Files.readAllBytes(root.resolve("native-ambiguous.bin"))).isEqualTo(bytes);
    } finally {
      boundary.destroy();
    }
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
  void directAppendRejectsInvalidBoundaryInputsBeforeRepositoryOrNativeMutation() throws Exception {
    Path root = Files.createDirectories(temp.resolve("append-validation-shared"));
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    SharedFolderUploadSessionRepository repository = mock(SharedFolderUploadSessionRepository.class);
    WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository, properties(root), boundary);
    String digest = sha256(new byte[] {1});

    assertStatus(400, () -> uploads.append(null, 0, new ByteArrayInputStream(new byte[] {1}), digest));
    assertStatus(400, () -> uploads.append("", 0, new ByteArrayInputStream(new byte[] {1}), digest));
    assertStatus(400, () -> uploads.append("x".repeat(129), 0,
        new ByteArrayInputStream(new byte[] {1}), digest));
    assertStatus(400, () -> uploads.append("id", -1,
        new ByteArrayInputStream(new byte[] {1}), digest));
    assertStatus(400, () -> uploads.append("id", 0, null, digest));
    assertStatus(400, () -> uploads.append("id", 0,
        new ByteArrayInputStream(new byte[] {1}), null));
    assertStatus(400, () -> uploads.append("id", 0,
        new ByteArrayInputStream(new byte[] {1}), " "));
    assertStatus(400, () -> uploads.append("id", 0,
        new ByteArrayInputStream(new byte[] {1}), "not-a-digest"));

    org.mockito.Mockito.verifyNoInteractions(access, repository, boundary);
  }

  @Test
  void nativeUploadMapsMissingConflictAndUnavailableStatusesThroughRealService() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-status-shared"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    for (int[] contract : java.util.List.of(
        new int[] {0xC0000034, 404},
        new int[] {0xC0000035, 409},
        new int[] {0xC0000001, 503},
        new int[] {0, 503})) {
      WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
      when(boundary.nativeMode()).thenReturn(true);
      when(boundary.directory("")).thenThrow(
          new dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException(
              "simulated native upload failure", contract[0]));
      SharedFolderUploadService uploads = new SharedFolderUploadService(
          access, repository(new ConcurrentHashMap<>()), properties(root), boundary);

      assertStatus(contract[1], () -> uploads.create(new SharedFolderUploadCreateRequest(
          "", "status-" + contract[1] + ".bin", 1, null, null)));
    }
    WindowsSharedFolderMutationBoundary semanticBoundary =
        mock(WindowsSharedFolderMutationBoundary.class);
    when(semanticBoundary.nativeMode()).thenReturn(true);
    when(semanticBoundary.directory("")).thenThrow(
        dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException
            .conflict("semantic native conflict"));
    SharedFolderUploadService semanticConflict = new SharedFolderUploadService(
        access, repository(new ConcurrentHashMap<>()), properties(root), semanticBoundary);
    assertStatus(409, () -> semanticConflict.create(new SharedFolderUploadCreateRequest(
        "", "semantic-conflict.bin", 1, null, null)));
  }

  @Test
  void nativeAppendCompleteAndCancelPreserveExactMissingConflictAndUnavailableStatuses()
      throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-lifecycle-status"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    for (int[] contract : java.util.List.of(
        new int[] {0xC0000034, 404},
        new int[] {0xC0000043, 409},
        new int[] {0xC0000001, 503},
        new int[] {0, 503})) {
      var failure = new dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge
          .NativeBoundaryException("native lifecycle failure", contract[0]);

      Map<String, SharedFolderUploadSession> appendStored = new ConcurrentHashMap<>();
      SharedFolderUploadSession appendSession = activeSession(
          "append-" + contract[1] + "-" + contract[0], account.getId(), "append-staging");
      appendStored.put(appendSession.getId(), appendSession);
      WindowsSharedFolderMutationBoundary appendBoundary = mock(WindowsSharedFolderMutationBoundary.class);
      when(appendBoundary.nativeMode()).thenReturn(true);
      when(appendBoundary.usableSystemBytes()).thenReturn(Long.MAX_VALUE);
      when(appendBoundary.createStaging(any())).thenThrow(failure);
      SharedFolderUploadService append = new SharedFolderUploadService(
          access, repository(appendStored), properties(root), appendBoundary);
      assertStatus(contract[1], () -> append.append(appendSession.getId(), 0,
          new ByteArrayInputStream(new byte[] {1}), sha256(new byte[] {1})));

      Map<String, SharedFolderUploadSession> completeStored = new ConcurrentHashMap<>();
      SharedFolderUploadSession completeSession = activeSession(
          "complete-" + contract[1] + "-" + contract[0], account.getId(), "complete-staging");
      completeSession.setNextOffset(1);
      completeStored.put(completeSession.getId(), completeSession);
      WindowsSharedFolderMutationBoundary completeBoundary = mock(WindowsSharedFolderMutationBoundary.class);
      when(completeBoundary.nativeMode()).thenReturn(true);
      when(completeBoundary.staging("complete-staging")).thenThrow(failure);
      SharedFolderUploadService complete = new SharedFolderUploadService(
          access, repository(completeStored), properties(root), completeBoundary);
      assertStatus(contract[1], () -> complete.complete(completeSession.getId(), false));

      Map<String, SharedFolderUploadSession> cancelStored = new ConcurrentHashMap<>();
      SharedFolderUploadSession cancelSession = activeSession(
          "cancel-" + contract[1] + "-" + contract[0], account.getId(), "cancel-staging");
      cancelStored.put(cancelSession.getId(), cancelSession);
      WindowsSharedFolderMutationBoundary cancelBoundary = mock(WindowsSharedFolderMutationBoundary.class);
      when(cancelBoundary.nativeMode()).thenReturn(true);
      when(cancelBoundary.deleteStagingIfExists("cancel-staging")).thenThrow(failure);
      SharedFolderUploadService cancel = new SharedFolderUploadService(
          access, repository(cancelStored), properties(root), cancelBoundary);
      assertStatus(contract[1], () -> cancel.cancel(cancelSession.getId()));
    }
  }

  @Test
  void nativeFinalizationRecoveryPropagatesUnavailableMetadataInsteadOfPending() throws Exception {
    Path root = Files.createDirectories(temp.resolve("native-finalization-recovery-unavailable"));
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadSession session = activeSession(
        "native-recovery", account.getId(), "staging-key");
    session.setState(SharedFolderUploadState.FINALIZING);
    session.setFinalizingIdentity("source-identity");
    session.setFinalizingReplace(true);
    session.setFinalizingTargetIdentity("target-identity");
    session.setFinalizingQuarantineKey("quarantine-key");
    session.setFinalizationState(SharedFolderUploadFinalizationState.PREPARED);
    session.setFinalizationLeaseToken("expired-token");
    session.setFinalizationLeaseExpiresAt(Instant.EPOCH);
    stored.put(session.getId(), session);
    WindowsSharedFolderMutationBoundary boundary = mock(WindowsSharedFolderMutationBoundary.class);
    when(boundary.nativeMode()).thenReturn(true);
    when(boundary.metadata(any())).thenThrow(
        new dev.christopherbell.sharedfolder.fs.WindowsSharedFolderNativeBridge.NativeBoundaryException(
            "native metadata unavailable", 0));
    SharedFolderUploadService uploads = new SharedFolderUploadService(
        access, repository(stored), properties(root), boundary);

    assertStatus(503, () -> uploads.status(session.getId()));

    assertThat(stored.get(session.getId()).getState()).isEqualTo(SharedFolderUploadState.FINALIZING);
    verify(boundary, org.mockito.Mockito.never()).restoreQuarantine(any(), any(), any(), any());
    verify(boundary, org.mockito.Mockito.never()).deleteQuarantine(any(), any());
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
  void portableLifecycleMapsMissingParentsAndUnavailableVisibleOrPrivateRootsExactly()
      throws Exception {
    Account account = new Account();
    account.setId("account-1");
    SharedFolderAccessService access = mock(SharedFolderAccessService.class);
    when(access.requireWrite()).thenReturn(account);

    Path availableRoot = Files.createDirectories(temp.resolve("portable-status-root"));
    SharedFolderUploadService available = new SharedFolderUploadService(
        access, repository(new ConcurrentHashMap<>()), properties(availableRoot));
    assertStatus(404, () -> available.create(new SharedFolderUploadCreateRequest(
        "missing-parent", "target.bin", 1, null, null)));

    SharedFolderUploadService unavailableVisible = new SharedFolderUploadService(
        access, repository(new ConcurrentHashMap<>()), properties(temp.resolve("absent-visible-root")));
    assertStatus(503, () -> unavailableVisible.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", 1, null, null)));

    Path privateRootFile = temp.resolve("private-root-file");
    Files.writeString(privateRootFile, "not-a-directory");
    SharedFolderProperties badPrivateProperties = new SharedFolderProperties(
        availableRoot, privateRootFile, DataSize.ofGigabytes(10), DataSize.ofMegabytes(8),
        DataSize.ofBytes(1), DataSize.ofGigabytes(1), Duration.ofDays(30),
        Duration.ofDays(180), true);
    SharedFolderUploadService unavailablePrivate = new SharedFolderUploadService(
        access, repository(new ConcurrentHashMap<>()), badPrivateProperties);
    assertStatus(503, () -> unavailablePrivate.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", 1, null, null)));

    Path lifecycleRoot = Files.createDirectories(temp.resolve("portable-lifecycle-root"));
    SharedFolderProperties lifecycleProperties = properties(lifecycleRoot);
    Map<String, SharedFolderUploadSession> stored = new ConcurrentHashMap<>();
    SharedFolderUploadService lifecycle = new SharedFolderUploadService(
        access, repository(stored), lifecycleProperties);
    byte[] content = new byte[] {7};
    SharedFolderUploadStatus upload = lifecycle.create(new SharedFolderUploadCreateRequest(
        "", "target.bin", content.length, sha256(content), null));
    lifecycle.append(upload.id(), 0, new ByteArrayInputStream(content), sha256(content));
    SharedFolderUploadStatus recovering = lifecycle.create(new SharedFolderUploadCreateRequest(
        "", "recovering.bin", content.length, sha256(content), null));
    lifecycle.append(recovering.id(), 0, new ByteArrayInputStream(content), sha256(content));
    SharedFolderUploadSession recoveringSession = stored.get(recovering.id());
    recoveringSession.setState(SharedFolderUploadState.FINALIZING);
    recoveringSession.setFinalizingIdentity("expected-staging-identity");
    recoveringSession.setFinalizingReplace(false);
    recoveringSession.setFinalizationLeaseToken("expired-lease");
    recoveringSession.setFinalizationLeaseExpiresAt(Instant.EPOCH);
    SharedFolderUploadStatus replacing = lifecycle.create(new SharedFolderUploadCreateRequest(
        "", "replacing.bin", content.length, sha256(content), null));
    lifecycle.append(replacing.id(), 0, new ByteArrayInputStream(content), sha256(content));
    SharedFolderUploadSession replacingSession = stored.get(replacing.id());
    replacingSession.setState(SharedFolderUploadState.FINALIZING);
    replacingSession.setFinalizingIdentity("expected-staging-identity");
    replacingSession.setFinalizingReplace(true);
    replacingSession.setFinalizingTargetIdentity("expected-target-identity");
    replacingSession.setFinalizingQuarantineKey("expected-quarantine-key");
    replacingSession.setFinalizationState(SharedFolderUploadFinalizationState.PREPARED);
    replacingSession.setFinalizationLeaseToken("expired-replacement-lease");
    replacingSession.setFinalizationLeaseExpiresAt(Instant.EPOCH);
    Path staging = lifecycleProperties.systemRoot().resolve("shared-folder-upload-staging");
    Path displaced = lifecycleProperties.systemRoot().resolve("displaced-staging");
    Files.move(staging, displaced);
    Files.createDirectory(staging);

    assertStatus(503, () -> lifecycle.complete(upload.id(), false));
    assertStatus(503, () -> lifecycle.cancel(upload.id()));
    assertStatus(503, () -> lifecycle.status(upload.id()));
    assertStatus(503, () -> lifecycle.status(recovering.id()));
    assertStatus(503, () -> lifecycle.status(replacing.id()));
    assertThat(Files.notExists(staging.resolve(stored.get(upload.id()).getStagingKey()))).isTrue();
    assertThat(Files.exists(displaced.resolve(stored.get(upload.id()).getStagingKey()))).isTrue();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void explicitReplacementUploadPreservesObservedTargetSpellingInPortableAndNativeModes()
      throws Exception {
    for (boolean nativeMode : java.util.List.of(false, true)) {
      Path root = Files.createDirectories(temp.resolve("canonical-upload-" + nativeMode));
      Files.writeString(root.resolve("Target.bin"), "target");
      SharedFolderProperties properties = properties(root);
      WindowsSharedFolderMutationBoundary boundary = nativeMode
          ? new WindowsSharedFolderMutationBoundary(properties)
          : WindowsSharedFolderMutationBoundary.inactive();
      if (nativeMode) boundary.initialize();
      try {
        Account account = new Account();
        account.setId("account-1");
        SharedFolderAccessService access = mock(SharedFolderAccessService.class);
        when(access.requireWrite()).thenReturn(account);
        String observed = new SharedFolderMutationService(access, properties, boundary)
            .observedToken("Target.bin");
        SharedFolderUploadService uploads = new SharedFolderUploadService(
            access, repository(new ConcurrentHashMap<>()), properties, boundary);

        SharedFolderUploadStatus upload = uploads.create(new SharedFolderUploadCreateRequest(
            "", "target.bin", 1, null, observed));

        assertThat(upload.name()).isEqualTo("Target.bin");
      } finally {
        if (nativeMode) boundary.destroy();
      }
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
    stubFinalizationRenewal(repository, sessions);
    return repository;
  }

  private SharedFolderUploadSessionRepository commitThenThrowRepository(
      Map<String, SharedFolderUploadSession> sessions, AtomicBoolean throwAfterCommit) {
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
        if (saved.getState() == SharedFolderUploadState.ACTIVE
            && saved.getNextOffset() > 0 && throwAfterCommit.compareAndSet(true, false)) {
          throw new org.springframework.dao.OptimisticLockingFailureException(
              "simulated acknowledged commit failure");
        }
        return copy(saved);
      }
    });
    when(repository.findById(any(String.class))).thenAnswer(invocation ->
        java.util.Optional.ofNullable(sessions.get(invocation.getArgument(0))).map(this::copy));
    stubFinalizationRenewal(repository, sessions);
    return repository;
  }

  private void stubFinalizationRenewal(
      SharedFolderUploadSessionRepository repository,
      Map<String, SharedFolderUploadSession> sessions) {
    org.mockito.Mockito.doAnswer(invocation -> {
      synchronized (sessions) {
        SharedFolderUploadSession current = sessions.get(invocation.getArgument(0));
        Instant now = invocation.getArgument(3);
        if (current == null
            || current.getState() != SharedFolderUploadState.APPENDING
            || !java.util.Objects.equals(current.getAppendLeaseToken(), invocation.getArgument(1))
            || !java.util.Objects.equals(current.getAppendOffset(), invocation.getArgument(2))
            || current.getAppendLeaseExpiresAt() == null
            || current.getAppendLeaseExpiresAt().isAfter(now)) {
          return 0L;
        }
        current.setAppendLeaseToken(invocation.getArgument(4));
        current.setAppendLeaseExpiresAt(invocation.getArgument(5));
        current.setUpdatedAt(invocation.getArgument(6));
        return 1L;
      }
    }).when(repository).claimExpiredAppendLease(
        any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    org.mockito.Mockito.doAnswer(invocation -> {
      synchronized (sessions) {
        SharedFolderUploadSession current = sessions.get(invocation.getArgument(0));
        if (current == null
            || current.getState() != SharedFolderUploadState.APPENDING
            || !java.util.Objects.equals(current.getAppendLeaseToken(), invocation.getArgument(1))
            || !java.util.Objects.equals(current.getAppendOffset(), invocation.getArgument(2))) {
          return 0L;
        }
        current.setAppendLeaseExpiresAt(invocation.getArgument(3));
        current.setUpdatedAt(invocation.getArgument(4));
        return 1L;
      }
    }).when(repository).renewAppendLease(
        any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    org.mockito.Mockito.doAnswer(invocation -> {
      synchronized (sessions) {
        SharedFolderUploadSession current = sessions.get(invocation.getArgument(0));
        if (current == null
            || current.getState() != SharedFolderUploadState.FINALIZING
            || !java.util.Objects.equals(
                current.getFinalizationLeaseToken(), invocation.getArgument(1))
            || current.getFinalizationState() != invocation.getArgument(2)) {
          return 0L;
        }
        current.setFinalizationLeaseExpiresAt(invocation.getArgument(3));
        current.setUpdatedAt(invocation.getArgument(4));
        return 1L;
      }
    }).when(repository).renewFinalizationLease(any(), any(), any(), any(), any());
    org.mockito.Mockito.doAnswer(invocation -> {
      synchronized (sessions) {
        SharedFolderUploadSession current = sessions.get(invocation.getArgument(0));
        Instant now = invocation.getArgument(3);
        if (current == null
            || current.getState() != SharedFolderUploadState.FINALIZING
            || !java.util.Objects.equals(
                current.getFinalizationLeaseToken(), invocation.getArgument(1))
            || current.getFinalizationState() != invocation.getArgument(2)
            || current.getFinalizationLeaseExpiresAt() != null
                && current.getFinalizationLeaseExpiresAt().isAfter(now)) {
          return 0L;
        }
        current.setFinalizationLeaseToken(invocation.getArgument(4));
        current.setFinalizationLeaseExpiresAt(invocation.getArgument(5));
        current.setUpdatedAt(invocation.getArgument(6));
        return 1L;
      }
    }).when(repository).claimExpiredFinalizationLease(
        any(), any(), any(), any(), any(), any(), any());
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
    copy.setFinalizationLeaseToken(source.getFinalizationLeaseToken());
    copy.setFinalizationLeaseExpiresAt(source.getFinalizationLeaseExpiresAt());
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

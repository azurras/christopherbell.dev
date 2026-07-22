package dev.christopherbell.sharedfolder.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.SharedFolderMediaProperties;
import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.media.MediaSourceBoundary.MediaSourceSnapshot;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.SliceImpl;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class MediaPlaybackServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

  @TempDir Path temp;
  private SharedFolderAccessService access;
  private MediaJobRepository jobs;
  private SharedFolderAuditRecorder audit;
  private MediaSourceBoundary sources;
  private MediaStorage storage;
  private MediaPlaybackService media;
  private Account account;
  private SharedFolderProperties folderProperties;
  private SharedFolderMediaProperties mediaProperties;

  @BeforeEach
  void setUp() throws Exception {
    Path shared = Files.createDirectory(temp.resolve("shared"));
    Path system = Files.createDirectory(temp.resolve("system"));
    folderProperties = new SharedFolderProperties(
        shared, system, DataSize.ofGigabytes(10), DataSize.ofMegabytes(8),
        DataSize.ofBytes(100), DataSize.ofGigabytes(250), Duration.ofDays(30),
        Duration.ofDays(180), true);
    mediaProperties = new SharedFolderMediaProperties(
        4, 2, Duration.ofMinutes(30), Duration.ofMillis(10), Duration.ofSeconds(1),
        DataSize.ofBytes(4), DataSize.ofBytes(100));
    access = mock(SharedFolderAccessService.class);
    jobs = mock(MediaJobRepository.class);
    audit = mock(SharedFolderAuditRecorder.class);
    sources = mock(MediaSourceBoundary.class);
    storage = new MediaStorage(folderProperties);
    storage.initialize();
    account = new Account();
    account.setId("account-1");
    when(access.requireRead()).thenReturn(account);
    when(jobs.countByStatusIn(any())).thenReturn(0L);
    when(jobs.countByOwnerIdAndStatusIn(any(), any())).thenReturn(0L);
    when(jobs.findByStatusIn(any())).thenReturn(List.of());
    when(jobs.findByStatusOrderByLastAccessedAtAscIdAsc(any(), any()))
        .thenReturn(new SliceImpl<>(List.of()));
    when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    media = new MediaPlaybackService(
        access, jobs, audit, sources, storage, folderProperties, mediaProperties,
        Clock.fixed(NOW, ZoneOffset.UTC), () -> "job-1");
  }

  @Test
  void directPlaybackIsOnlyABrowserProbeAndNeverClaimsCodecEvidence() {
    when(sources.resolve("audio/song.flac"))
        .thenReturn(source("audio/song.flac", 20, NOW.minusSeconds(1)));
    when(sources.resolve("video/source.mkv"))
        .thenReturn(source("video/source.mkv", 30, NOW.minusSeconds(2)));

    assertThat(media.playback("audio/song.flac").mode())
        .isEqualTo(MediaPlaybackMode.DIRECT_PROBE);
    assertThat(media.playback("video/source.mkv").mode())
        .isEqualTo(MediaPlaybackMode.DIRECT_PROBE);
    verify(access, org.mockito.Mockito.times(2)).requireRead();
  }

  @Test
  void fallbackCreatesOpaqueFixedProfileDescriptorWithoutArguments() throws Exception {
    MediaSourceSnapshot source = source("video/source.mkv", 30, NOW.minusSeconds(2));
    when(sources.resolve(source.relativePath())).thenReturn(source);
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(any(), any()))
        .thenReturn(Optional.empty());

    MediaPlaybackDescriptor result =
        media.requestFallback(source.relativePath(), MediaOutputProfile.VIDEO_MP4);

    assertThat(result.status()).isEqualTo(MediaJobStatus.QUEUED);
    assertThat(result.jobId()).isEqualTo("job-1");
    var captor = org.mockito.ArgumentCaptor.forClass(MediaJob.class);
    verify(jobs, org.mockito.Mockito.times(2)).save(captor.capture());
    MediaJob saved = captor.getValue();
    assertThat(saved.getSourcePath()).isEqualTo("video/source.mkv");
    assertThat(saved.getProfile()).isEqualTo(MediaOutputProfile.VIDEO_MP4);
    assertThat(saved.getDeadline()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    String descriptor = storage.readDescriptor("job-1");
    assertThat(descriptor)
        .contains("\"schemaVersion\":1", "\"profile\":\"VIDEO_MP4\"")
        .contains("\"cancellationPath\"")
        .contains(source.absolutePath().toString().replace("\\", "\\\\"))
        .doesNotContain("arguments", "ffmpeg", "ffprobe", "command");
  }

  @Test
  void cacheKeyChangesWithSourceMetadataAndProfileVersion() {
    MediaSourceSnapshot first = source("video/a.mkv", 10, NOW.minusSeconds(5));
    MediaSourceSnapshot changedSize = source("video/a.mkv", 11, NOW.minusSeconds(5));
    MediaSourceSnapshot changedTime = source("video/a.mkv", 10, NOW.minusSeconds(4));

    assertThat(MediaCacheKeys.forSource(first, MediaOutputProfile.VIDEO_MP4, 1))
        .isNotEqualTo(MediaCacheKeys.forSource(changedSize, MediaOutputProfile.VIDEO_MP4, 1))
        .isNotEqualTo(MediaCacheKeys.forSource(changedTime, MediaOutputProfile.VIDEO_MP4, 1));
    assertThat(MediaCacheKeys.forSource(first, MediaOutputProfile.VIDEO_MP4, 1))
        .isNotEqualTo(MediaCacheKeys.forSource(first, MediaOutputProfile.VIDEO_MP4, 2));
  }

  @Test
  void readyCacheIsReusedWithoutConsumingQueueCapacity() throws Exception {
    MediaSourceSnapshot source = source("audio/source.wav", 30, NOW.minusSeconds(2));
    when(sources.resolve(source.relativePath())).thenReturn(source);
    String key = MediaCacheKeys.forSource(source, MediaOutputProfile.AUDIO_M4A, 1);
    MediaJob ready = job("ready-1", key, MediaJobStatus.READY);
    ready.setSourcePath(source.relativePath());
    ready.setSourceSize(source.size());
    ready.setSourceModifiedAt(source.modifiedAt());
    ready.setProfile(MediaOutputProfile.AUDIO_M4A);
    storage.writeReadyForTest(ready, "ready-bytes".getBytes());
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
        key, MediaJobStatus.READY)).thenReturn(Optional.of(ready));

    MediaPlaybackDescriptor result =
        media.requestFallback(source.relativePath(), MediaOutputProfile.AUDIO_M4A);

    assertThat(result.mode()).isEqualTo(MediaPlaybackMode.READY);
    assertThat(result.jobId()).isEqualTo("ready-1");
    verify(jobs).save(ready);
    assertThat(ready.getLastAccessedAt()).isEqualTo(NOW);
  }

  @Test
  void completedCacheIsVisibleToAnotherAuthorizedReader() throws Exception {
    MediaSourceSnapshot source = source("audio/source.wav", 30, NOW.minusSeconds(2));
    String key = MediaCacheKeys.forSource(source, MediaOutputProfile.AUDIO_M4A, 1);
    MediaJob ready = job("ready-1", key, MediaJobStatus.READY);
    ready.setSourcePath(source.relativePath());
    ready.setSourceSize(source.size());
    ready.setSourceModifiedAt(source.modifiedAt());
    ready.setProfile(MediaOutputProfile.AUDIO_M4A);
    storage.writeReadyForTest(ready, "ready-bytes".getBytes());
    when(sources.resolve(source.relativePath())).thenReturn(source);
    when(jobs.findById("ready-1")).thenReturn(Optional.of(ready));
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
        key, MediaJobStatus.READY)).thenReturn(Optional.of(ready));

    account.setId("account-2");

    assertThat(media.job("ready-1").status()).isEqualTo(MediaJobStatus.READY);
    assertThat(media.requestFallback(source.relativePath(), MediaOutputProfile.AUDIO_M4A).jobId())
        .isEqualTo("ready-1");
  }

  @Test
  void activeCacheWorkIsReusedOnlyByItsOwner() {
    MediaSourceSnapshot source = source("video/source.mkv", 30, NOW.minusSeconds(2));
    String key = MediaCacheKeys.forSource(source, MediaOutputProfile.VIDEO_MP4, 1);
    MediaJob active = job("job-existing", key, MediaJobStatus.TRANSCODING);
    when(sources.resolve(source.relativePath())).thenReturn(source);
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
        key, MediaJobStatus.READY)).thenReturn(Optional.empty());
    when(jobs.findFirstByCacheKeyAndStatusInOrderByCreatedAtAsc(
        key, MediaJobStatus.active())).thenReturn(Optional.of(active));

    assertThat(media.requestFallback(source.relativePath(), MediaOutputProfile.VIDEO_MP4).jobId())
        .isEqualTo("job-existing");

    account.setId("account-2");
    assertStatus(HttpStatus.TOO_MANY_REQUESTS,
        () -> media.requestFallback(source.relativePath(), MediaOutputProfile.VIDEO_MP4));
  }

  @Test
  void admissionEvictsOldestReadyCacheToCoverAllActiveReservations() throws Exception {
    MediaStorage boundedStorage = mock(MediaStorage.class);
    MediaJob active = job("active", "c".repeat(64), MediaJobStatus.QUEUED);
    active.setReservedBytes(100);
    MediaJob oldest = job("oldest", "d".repeat(64), MediaJobStatus.READY);
    MediaJob newest = job("newest", "e".repeat(64), MediaJobStatus.READY);
    oldest.setLastAccessedAt(NOW.minusSeconds(20));
    newest.setLastAccessedAt(NOW.minusSeconds(10));
    AtomicBoolean oldestDeleted = new AtomicBoolean();
    when(boundedStorage.readyLength(any(), anyLong())).thenAnswer(invocation -> {
      MediaJob candidate = invocation.getArgument(0);
      return oldestDeleted.get() && candidate == oldest
          ? OptionalLong.empty() : OptionalLong.of(10);
    });
    when(boundedStorage.usableSpace()).thenAnswer(
        ignored -> oldestDeleted.get() ? 350L : 250L);
    when(boundedStorage.deleteReady(oldest)).thenAnswer(ignored -> {
      oldestDeleted.set(true);
      return true;
    });
    when(jobs.findByStatusIn(MediaJobStatus.active())).thenReturn(List.of(active));
    when(jobs.findByStatusOrderByLastAccessedAtAscIdAsc(any(), any()))
        .thenReturn(new SliceImpl<>(List.of(oldest, newest)));
    MediaSourceSnapshot source = source("video/new.mkv", 30, NOW.minusSeconds(2));
    when(sources.resolve(source.relativePath())).thenReturn(source);
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(any(), any()))
        .thenReturn(Optional.empty());
    MediaPlaybackService bounded = new MediaPlaybackService(
        access, jobs, audit, sources, boundedStorage, folderProperties, mediaProperties,
        Clock.fixed(NOW, ZoneOffset.UTC), () -> "job-new");

    assertThat(bounded.requestFallback(source.relativePath(), MediaOutputProfile.VIDEO_MP4).jobId())
        .isEqualTo("job-new");
    verify(boundedStorage).deleteReady(oldest);
    verify(boundedStorage, org.mockito.Mockito.never()).deleteReady(newest);
  }

  @Test
  void onlyOneWorkerDescriptorIsPublishedUntilTheActiveJobFinishes() throws Exception {
    AtomicInteger ids = new AtomicInteger();
    MediaPlaybackService sequential = new MediaPlaybackService(
        access, jobs, audit, sources, storage, folderProperties, mediaProperties,
        Clock.fixed(NOW, ZoneOffset.UTC), () -> "job-" + ids.incrementAndGet());
    MediaSourceSnapshot first = source("video/first.mkv", 30, NOW.minusSeconds(2));
    MediaSourceSnapshot second = source("video/second.mkv", 40, NOW.minusSeconds(3));
    when(sources.resolve(first.relativePath())).thenReturn(first);
    when(sources.resolve(second.relativePath())).thenReturn(second);
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(any(), any()))
        .thenReturn(Optional.empty());
    sequential.requestFallback(first.relativePath(), MediaOutputProfile.VIDEO_MP4);
    MediaJob published = job("job-1", "published", MediaJobStatus.QUEUED);
    published.setDescriptorPublished(true);
    when(jobs.findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(
        MediaJobStatus.active())).thenReturn(Optional.of(published));
    sequential.requestFallback(second.relativePath(), MediaOutputProfile.VIDEO_MP4);

    assertThat(storage.readDescriptor("job-1")).contains("\"jobId\":\"job-1\"");
    assertThatThrownBy(() -> storage.readDescriptor("job-2")).isInstanceOf(java.io.IOException.class);
  }

  @Test
  void admissionRejectsGlobalAndPerAccountQueueSaturation() {
    MediaSourceSnapshot source = source("video/source.mkv", 30, NOW.minusSeconds(2));
    when(sources.resolve(source.relativePath())).thenReturn(source);
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(any(), any()))
        .thenReturn(Optional.empty());
    when(jobs.countByStatusIn(MediaJobStatus.active())).thenReturn(4L);

    assertStatus(HttpStatus.TOO_MANY_REQUESTS,
        () -> media.requestFallback(source.relativePath(), MediaOutputProfile.VIDEO_MP4));

    when(jobs.countByStatusIn(MediaJobStatus.active())).thenReturn(0L);
    when(jobs.countByOwnerIdAndStatusIn("account-1", MediaJobStatus.active())).thenReturn(2L);
    assertStatus(HttpStatus.TOO_MANY_REQUESTS,
        () -> media.requestFallback(source.relativePath(), MediaOutputProfile.VIDEO_MP4));
  }

  @Test
  void admissionRejectsInsufficientFreeSpaceBeforePersistingAJob() throws Exception {
    MediaSourceSnapshot source = source("video/source.mkv", 30, NOW.minusSeconds(2));
    when(sources.resolve(source.relativePath())).thenReturn(source);
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(any(), any()))
        .thenReturn(Optional.empty());
    storage.setUsableSpaceForTest(99);

    assertStatus(HttpStatus.INSUFFICIENT_STORAGE,
        () -> media.requestFallback(source.relativePath(), MediaOutputProfile.VIDEO_MP4));
    verify(jobs, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void ownerCanCancelQueuedJobAndOtherAccountCannotObserveIt() {
    MediaJob queued = job("job-1", "cache-1", MediaJobStatus.QUEUED);
    when(jobs.findById("job-1")).thenReturn(Optional.of(queued));
    when(jobs.cancelActive("job-1", "account-1", NOW)).thenReturn(1L);

    assertThat(media.cancel("job-1").status()).isEqualTo(MediaJobStatus.CANCELED);

    account.setId("account-2");
    assertStatus(HttpStatus.NOT_FOUND, () -> media.job("job-1"));
  }

  @Test
  void workerStatusIsValidatedAndReflectedInOwnedPolling() throws Exception {
    MediaJob queued = job("job-1", "a".repeat(64), MediaJobStatus.QUEUED);
    queued.setDescriptorPublished(true);
    when(jobs.findById("job-1")).thenReturn(Optional.of(queued));
    storage.writeStatusForTest(queued, MediaJobStatus.BUFFERING, 4, null);

    MediaPlaybackDescriptor response = media.job("job-1");

    assertThat(response.status()).isEqualTo(MediaJobStatus.BUFFERING);
    assertThat(queued.getOutputBytes()).isEqualTo(4);
    verify(jobs, atLeastOnce()).save(queued);
  }

  @Test
  void workerReadyPublishBecomesReusableCacheState() throws Exception {
    MediaSourceSnapshot source = source("video/source.mkv", 30, NOW.minusSeconds(2));
    String cacheKey = MediaCacheKeys.forSource(source, MediaOutputProfile.VIDEO_MP4, 1);
    MediaJob queued = job("job-1", cacheKey, MediaJobStatus.QUEUED);
    when(jobs.findById("job-1")).thenReturn(Optional.of(queued));
    storage.writeReadyForTest(queued, "ready".getBytes());
    storage.writeStatusForTest(queued, MediaJobStatus.READY, 5, null);

    assertThat(media.job("job-1").status()).isEqualTo(MediaJobStatus.READY);

    when(sources.resolve(source.relativePath())).thenReturn(source);
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
        queued.getCacheKey(), MediaJobStatus.READY)).thenReturn(Optional.of(queued));
    assertThat(media.requestFallback(source.relativePath(), queued.getProfile()).mode())
        .isEqualTo(MediaPlaybackMode.READY);
  }

  @Test
  void workerReadyClaimIsIgnoredWhenActualOutputExceedsTheLimit() throws Exception {
    MediaJob queued = job("job-1", "f".repeat(64), MediaJobStatus.QUEUED);
    queued.setDescriptorPublished(true);
    when(jobs.findById("job-1")).thenReturn(Optional.of(queued));
    storage.writeReadyForTest(queued, new byte[101]);
    storage.writeStatusForTest(queued, MediaJobStatus.READY, 100, null);

    assertThat(media.job("job-1").status()).isEqualTo(MediaJobStatus.FAILED);
    assertThat(queued.getFailureCategory()).isEqualTo("output_limit");
  }

  @Test
  void readyTransitionPublishesTheNextQueuedWorkerDescriptor() throws Exception {
    MediaJob first = job("job-1", "1".repeat(64), MediaJobStatus.BUFFERING);
    first.setDescriptorPublished(true);
    MediaJob second = job("job-2", "2".repeat(64), MediaJobStatus.QUEUED);
    second.setSourcePath("video/second.mkv");
    MediaSourceSnapshot secondSource = source(
        second.getSourcePath(), second.getSourceSize(), second.getSourceModifiedAt());
    second.setCacheKey(MediaCacheKeys.forSource(
        secondSource, second.getProfile(), second.getProfileVersion()));
    when(jobs.findById("job-1")).thenReturn(Optional.of(first));
    when(jobs.findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(
        MediaJobStatus.QUEUED)).thenReturn(Optional.of(second));
    when(sources.resolve(second.getSourcePath())).thenReturn(secondSource);
    storage.writeReadyForTest(first, "ready".getBytes());
    storage.writeStatusForTest(first, MediaJobStatus.READY, 5, null);

    assertThat(media.job("job-1").status()).isEqualTo(MediaJobStatus.READY);
    assertThat(storage.readDescriptor("job-2")).contains("\"jobId\":\"job-2\"");
    assertThat(second.isDescriptorPublished()).isTrue();
  }

  @Test
  void schedulerReconcilesAnUnattendedPublishedJobAndAdvancesTheQueue() throws Exception {
    MediaJob first = job("job-1", "3".repeat(64), MediaJobStatus.BUFFERING);
    first.setDescriptorPublished(true);
    MediaJob second = job("job-2", "4".repeat(64), MediaJobStatus.QUEUED);
    second.setSourcePath("video/second.mkv");
    MediaSourceSnapshot secondSource = source(
        second.getSourcePath(), second.getSourceSize(), second.getSourceModifiedAt());
    second.setCacheKey(MediaCacheKeys.forSource(
        secondSource, second.getProfile(), second.getProfileVersion()));
    when(jobs.findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(
        MediaJobStatus.active())).thenAnswer(ignored ->
            first.isDescriptorPublished() && !first.getStatus().terminal()
                ? Optional.of(first) : Optional.empty());
    when(jobs.findFirstByStatusAndDescriptorPublishedFalseOrderByCreatedAtAsc(
        MediaJobStatus.QUEUED)).thenReturn(Optional.of(second));
    when(sources.resolve(second.getSourcePath())).thenReturn(secondSource);
    storage.writeReadyForTest(first, "ready".getBytes());
    storage.writeStatusForTest(first, MediaJobStatus.READY, 5, null);

    media.promoteQueuedJob();

    assertThat(first.getStatus()).isEqualTo(MediaJobStatus.READY);
    assertThat(storage.readDescriptor("job-2")).contains("\"jobId\":\"job-2\"");
  }

  @Test
  void schedulerRepublishesACrashInterruptedDescriptor() throws Exception {
    MediaJob published = job("job-1", "5".repeat(64), MediaJobStatus.QUEUED);
    published.setDescriptorPublished(true);
    MediaSourceSnapshot source = source(
        published.getSourcePath(), published.getSourceSize(), published.getSourceModifiedAt());
    published.setCacheKey(MediaCacheKeys.forSource(
        source, published.getProfile(), published.getProfileVersion()));
    when(jobs.findFirstByDescriptorPublishedTrueAndStatusInOrderByCreatedAtAsc(
        MediaJobStatus.active())).thenReturn(Optional.of(published));
    when(sources.resolve(published.getSourcePath())).thenReturn(source);

    media.promoteQueuedJob();

    assertThat(storage.readDescriptor("job-1")).contains("\"jobId\":\"job-1\"");
  }

  @Test
  void windowsCacheIdentityCollapsesPathCaseAliases() {
    MediaSourceSnapshot upper = source("Folder/Track.MKV", 10, NOW.minusSeconds(5));
    MediaSourceSnapshot lower = source("folder/track.mkv", 10, NOW.minusSeconds(5));

    if (java.io.File.separatorChar == '\\') {
      assertThat(MediaCacheKeys.forSource(upper, MediaOutputProfile.VIDEO_MP4, 1))
          .isEqualTo(MediaCacheKeys.forSource(lower, MediaOutputProfile.VIDEO_MP4, 1));
    } else {
      assertThat(MediaCacheKeys.forSource(upper, MediaOutputProfile.VIDEO_MP4, 1))
          .isNotEqualTo(MediaCacheKeys.forSource(lower, MediaOutputProfile.VIDEO_MP4, 1));
    }
  }

  @Test
  void readyJobUrlIsInvalidatedWhenTheSourceRevisionChanges() {
    MediaSourceSnapshot original = source("video/source.mkv", 30, NOW.minusSeconds(2));
    MediaJob ready = job("ready-1", MediaCacheKeys.forSource(
        original, MediaOutputProfile.VIDEO_MP4, 1), MediaJobStatus.READY);
    when(jobs.findById("ready-1")).thenReturn(Optional.of(ready));
    when(sources.resolve(ready.getSourcePath())).thenReturn(source(
        ready.getSourcePath(), ready.getSourceSize() + 1, ready.getSourceModifiedAt()));

    assertStatus(HttpStatus.NOT_FOUND, () -> media.job("ready-1"));
  }

  @Test
  void oneBadRequestIsAuditedWithoutCorruptingAnotherReadyJob() throws Exception {
    when(sources.resolve("bad.mkv"))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));
    MediaSourceSnapshot source = source("video/source.mkv", 30, NOW.minusSeconds(2));
    MediaJob ready = job("ready-1", MediaCacheKeys.forSource(
        source, MediaOutputProfile.VIDEO_MP4, 1), MediaJobStatus.READY);
    storage.writeReadyForTest(ready, "ready".getBytes());
    when(sources.resolve(ready.getSourcePath())).thenReturn(source);
    when(jobs.findById("ready-1")).thenReturn(Optional.of(ready));

    assertStatus(HttpStatus.NOT_FOUND,
        () -> media.requestFallback("bad.mkv", MediaOutputProfile.VIDEO_MP4));
    assertThat(media.job("ready-1").status()).isEqualTo(MediaJobStatus.READY);
    verify(audit).recordFailureOnce(any(), any(), any());
  }

  private MediaSourceSnapshot source(String relative, long size, Instant modifiedAt) {
    return new MediaSourceSnapshot(relative, temp.resolve("shared").resolve(relative), size, modifiedAt);
  }

  private MediaJob job(String id, String cacheKey, MediaJobStatus status) {
    MediaJob job = new MediaJob();
    job.setId(id);
    job.setOwnerId("account-1");
    job.setSourcePath("video/source.mkv");
    job.setSourceSize(30);
    job.setSourceModifiedAt(NOW.minusSeconds(2));
    job.setProfile(MediaOutputProfile.VIDEO_MP4);
    job.setProfileVersion(1);
    job.setCacheKey(cacheKey);
    job.setStatus(status);
    job.setCreatedAt(NOW);
    job.setUpdatedAt(NOW);
    job.setDeadline(NOW.plus(Duration.ofMinutes(30)));
    return job;
  }

  private void assertStatus(HttpStatus expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(expected));
  }
}

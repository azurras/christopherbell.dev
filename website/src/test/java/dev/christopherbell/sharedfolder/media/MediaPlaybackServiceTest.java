package dev.christopherbell.sharedfolder.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
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

  @BeforeEach
  void setUp() throws Exception {
    Path shared = Files.createDirectory(temp.resolve("shared"));
    Path system = Files.createDirectory(temp.resolve("system"));
    SharedFolderProperties folderProperties = new SharedFolderProperties(
        shared, system, DataSize.ofGigabytes(10), DataSize.ofMegabytes(8),
        DataSize.ofBytes(100), DataSize.ofGigabytes(250), Duration.ofDays(30),
        Duration.ofDays(180), true);
    SharedFolderMediaProperties mediaProperties = new SharedFolderMediaProperties(
        4, 2, Duration.ofMinutes(30), Duration.ofMillis(10), Duration.ofSeconds(1),
        DataSize.ofBytes(4), DataSize.ofGigabytes(20));
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
    when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    media = new MediaPlaybackService(
        access, jobs, audit, sources, storage, folderProperties, mediaProperties,
        Clock.fixed(NOW, ZoneOffset.UTC), () -> "job-1");
  }

  @Test
  void compatibleMediaUsesOriginalAndUnknownContainerRequiresFallbackInspection() {
    when(sources.resolve("audio/song.flac"))
        .thenReturn(source("audio/song.flac", 20, NOW.minusSeconds(1)));
    when(sources.resolve("video/source.mkv"))
        .thenReturn(source("video/source.mkv", 30, NOW.minusSeconds(2)));

    assertThat(media.playback("audio/song.flac").mode()).isEqualTo(MediaPlaybackMode.DIRECT);
    assertThat(media.playback("video/source.mkv").mode())
        .isEqualTo(MediaPlaybackMode.FALLBACK_REQUIRED);
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
    verify(jobs).save(captor.capture());
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
    storage.writeReadyForTest(ready, "ready-bytes".getBytes());
    when(jobs.findFirstByCacheKeyAndStatusOrderByUpdatedAtDesc(
        key, MediaJobStatus.READY)).thenReturn(Optional.of(ready));

    MediaPlaybackDescriptor result =
        media.requestFallback(source.relativePath(), MediaOutputProfile.AUDIO_M4A);

    assertThat(result.mode()).isEqualTo(MediaPlaybackMode.READY);
    assertThat(result.jobId()).isEqualTo("ready-1");
    verify(jobs, org.mockito.Mockito.never()).save(any());
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
    when(jobs.findById("job-1")).thenReturn(Optional.of(queued));
    storage.writeStatusForTest(queued, MediaJobStatus.BUFFERING, 4, null);

    MediaPlaybackDescriptor response = media.job("job-1");

    assertThat(response.status()).isEqualTo(MediaJobStatus.BUFFERING);
    assertThat(queued.getOutputBytes()).isEqualTo(4);
    verify(jobs).save(queued);
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
  void oneBadRequestIsAuditedWithoutCorruptingAnotherReadyJob() {
    when(sources.resolve("bad.mkv"))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));
    MediaJob ready = job("ready-1", "cache-1", MediaJobStatus.READY);
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

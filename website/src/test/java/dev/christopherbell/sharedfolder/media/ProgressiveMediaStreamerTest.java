package dev.christopherbell.sharedfolder.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.SharedFolderMediaProperties;
import dev.christopherbell.configuration.SharedFolderProperties;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ProgressiveMediaStreamerTest {
  @TempDir Path temp;
  private MediaStorage storage;
  private MediaJobRepository jobs;
  private ProgressiveMediaStreamer streamer;
  private MediaJob job;
  private SharedFolderProperties folders;

  @BeforeEach
  void setUp() throws Exception {
    Path shared = Files.createDirectory(temp.resolve("shared"));
    Path system = Files.createDirectory(temp.resolve("system"));
    folders = new SharedFolderProperties(
        shared, system, DataSize.ofGigabytes(10), DataSize.ofMegabytes(8),
        DataSize.ofBytes(10), DataSize.ofGigabytes(1), Duration.ofDays(1),
        Duration.ofDays(1), true);
    SharedFolderMediaProperties media = new SharedFolderMediaProperties(
        4, 2, Duration.ofMinutes(1), Duration.ofMillis(10), Duration.ofSeconds(1),
        DataSize.ofBytes(4), DataSize.ofMegabytes(10));
    storage = new MediaStorage(folders);
    storage.initialize();
    jobs = mock(MediaJobRepository.class);
    streamer = new ProgressiveMediaStreamer(storage, jobs, media);
    job = new MediaJob();
    job.setId("job-1");
    job.setOwnerId("account-1");
    job.setCacheKey("a".repeat(64));
    job.setProfile(MediaOutputProfile.VIDEO_MP4);
    job.setStatus(MediaJobStatus.BUFFERING);
    job.setDeadline(Instant.now().plusSeconds(60));
  }

  @Test
  void progressiveCopyStartsAtInitialBufferAndContinuesUntilReady() throws Exception {
    Files.write(storage.partialPath(job), "frag".getBytes());
    AtomicInteger checks = new AtomicInteger();
    when(jobs.findById("job-1")).thenAnswer(ignored -> {
      if (checks.incrementAndGet() == 2) {
        Files.write(storage.partialPath(job), "ment".getBytes(),
            java.nio.file.StandardOpenOption.APPEND);
        storage.writeReadyForTest(job, "fragment".getBytes());
        job.setStatus(MediaJobStatus.READY);
      }
      return Optional.of(job);
    });
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    streamer.copyGrowing(job, output);

    assertThat(output.toString()).isEqualTo("fragment");
    assertThat(checks.get()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void terminalFailureStopsBoundedPollingWithoutLeakingFailureText() throws Exception {
    job.setStatus(MediaJobStatus.FAILED);
    job.setFailureCategory("decode_failed");
    when(jobs.findById("job-1")).thenReturn(Optional.of(job));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    streamer.copyGrowing(job, output);

    assertThat(output.size()).isZero();
  }

  @Test
  void readyOutputSupportsSingleRangeWithoutLoadingTheFile() throws Exception {
    job.setStatus(MediaJobStatus.READY);
    storage.writeReadyForTest(job, "0123456789".getBytes());

    var selection = streamer.openReady(job, "bytes=2-5");

    assertThat(selection.start()).isEqualTo(2);
    assertThat(selection.length()).isEqualTo(4);
    assertThat(selection.totalLength()).isEqualTo(10);
    assertThat(selection.partial()).isTrue();
    assertThat(storage.deleteReady(job)).isFalse();
    selection.close();
    assertThat(storage.deleteReady(job)).isTrue();
  }

  @Test
  void readyLeaseBlocksConcurrentEvictionDuringRangeSelection() throws Exception {
    CountDownLatch lengthStarted = new CountDownLatch(1);
    CountDownLatch continueLength = new CountDownLatch(1);
    MediaStorage coordinated = new MediaStorage(folders) {
      @Override
      public OptionalLong readyLength(MediaJob current, long maximum) throws java.io.IOException {
        lengthStarted.countDown();
        try {
          if (!continueLength.await(2, TimeUnit.SECONDS)) {
            throw new java.io.IOException("Timed out coordinating ready selection");
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new java.io.IOException("Ready selection was interrupted", exception);
        }
        return super.readyLength(current, maximum);
      }
    };
    coordinated.initialize();
    job.setStatus(MediaJobStatus.READY);
    coordinated.writeReadyForTest(job, "0123456789".getBytes());
    ProgressiveMediaStreamer selecting = new ProgressiveMediaStreamer(
        coordinated, jobs, new SharedFolderMediaProperties(
            4, 2, Duration.ofMinutes(1), Duration.ofMillis(10), Duration.ofSeconds(1),
            DataSize.ofBytes(4), DataSize.ofMegabytes(10)));

    try (var executor = Executors.newSingleThreadExecutor()) {
      var selectionFuture = executor.submit(() -> selecting.openReady(job, "bytes=2-5"));
      assertThat(lengthStarted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(coordinated.deleteReady(job)).isFalse();
      continueLength.countDown();
      try (var selection = selectionFuture.get(2, TimeUnit.SECONDS)) {
        assertThat(selection.length()).isEqualTo(4);
      }
    } finally {
      continueLength.countDown();
    }
    assertThat(coordinated.deleteReady(job)).isTrue();
  }

  @Test
  void progressiveCopyFollowsAtomicWorkerPublishWithoutWaitingForMongoPolling() throws Exception {
    storage.writeReadyForTest(job, "fragment".getBytes());
    storage.writeStatusForTest(job, MediaJobStatus.READY, 8, null);
    when(jobs.findById("job-1")).thenReturn(Optional.of(job));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    streamer.copyGrowing(job, output);

    assertThat(output.toString()).isEqualTo("fragment");
  }

  @Test
  void progressiveCopyRetriesReadyOutputWhenPartialMovesDuringOpen() throws Exception {
    MediaStorage racingStorage = new MediaStorage(folders) {
      private boolean published;

      @Override
      public long copy(
          MediaJob current, boolean ready, long position, long length,
          java.io.OutputStream output, long maximum) throws java.io.IOException {
        if (!ready && !published) {
          published = true;
          writeReadyForTest(current, "fragment".getBytes());
          writeStatusForTest(current, MediaJobStatus.READY, 8, null);
          Files.deleteIfExists(partialPath(current));
          throw new java.nio.file.NoSuchFileException(partialPath(current).toString());
        }
        return super.copy(current, ready, position, length, output, maximum);
      }
    };
    racingStorage.initialize();
    Files.write(racingStorage.partialPath(job), "frag".getBytes());
    when(jobs.findById("job-1")).thenReturn(Optional.of(job));
    ProgressiveMediaStreamer racing = new ProgressiveMediaStreamer(racingStorage, jobs,
        new SharedFolderMediaProperties(
            4, 2, Duration.ofMinutes(1), Duration.ofMillis(10), Duration.ofSeconds(1),
            DataSize.ofBytes(4), DataSize.ofMegabytes(10)));
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    racing.copyGrowing(job, output);

    assertThat(output.toString()).isEqualTo("fragment");
  }
}

package dev.christopherbell.sharedfolder.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioDurationRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderRadioResponse;
import dev.christopherbell.sharedfolder.service.SharedFolderCatalogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntUnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderRadioServiceTest {
  private static final Instant START = Instant.parse("2026-07-25T12:00:00Z");

  @Test
  void current_whenMusicHasNoAudioTracks_persistsInitialEmptyStationIdentity() {
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(List.of(), repository, new MutableClock(START), 0);

    SharedFolderRadioResponse response = service.current();

    assertThat(response.status()).isEqualTo(SharedFolderRadioResponse.Status.EMPTY);
    assertThat(response.playback()).isNull();
    assertThat(repository.saved()).isNotNull();
    assertThat(repository.saved().state()).isEqualTo(SharedFolderRadioDocument.State.EMPTY);
    assertThat(repository.saved().stationSequence()).isEqualTo(1);
    assertThat(repository.saved().path()).isNull();
    assertThat(repository.saved().startedAt()).isNull();
    assertThat(repository.saved().durationSeconds()).isNull();
  }

  @Test
  void current_whenActiveTrackDisappears_persistsEmptyThenRestartsWithFreshIdentity() {
    SharedDirectoryEntry track = track("Music/song.mp3");
    AtomicReference<List<SharedDirectoryEntry>> tracks =
        new AtomicReference<>(List.of(track));
    MutableClock clock = new MutableClock(START);
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(tracks, repository, clock, 0);
    SharedFolderRadioResponse initial = service.current();
    tracks.set(List.of());
    clock.advanceSeconds(10);

    SharedFolderRadioResponse empty = service.current();

    assertThat(empty.status()).isEqualTo(SharedFolderRadioResponse.Status.EMPTY);
    assertThat(repository.saved().stationSequence()).isEqualTo(2);
    assertThat(repository.saved().state()).isEqualTo(SharedFolderRadioDocument.State.EMPTY);
    assertThat(repository.saved().path()).isNull();
    assertThat(repository.saved().startedAt()).isNull();
    tracks.set(List.of(track));
    clock.advanceSeconds(5);

    SharedFolderRadioResponse restarted = service.current();

    assertThat(restarted.playback().stationSequence()).isEqualTo(3);
    assertThat(restarted.playback().startedAt()).isEqualTo(START.plusSeconds(15));
    assertThat(restarted.playback().positionSeconds()).isZero();
    assertThat(initial.playback().stationSequence()).isEqualTo(1);
  }

  @Test
  void reportDuration_whenActiveTrackDisappears_tombstonesBeforeFreshReappearance() {
    SharedDirectoryEntry track = track("Music/song.mp3");
    AtomicReference<List<SharedDirectoryEntry>> tracks =
        new AtomicReference<>(List.of(track));
    MutableClock clock = new MutableClock(START);
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(tracks, repository, clock, 0);
    SharedFolderRadioResponse initial = service.current();
    tracks.set(List.of());
    clock.advanceSeconds(10);

    assertThatThrownBy(() -> service.reportDuration(new SharedFolderRadioDurationRequest(
        initial.playback().stationSequence(), track.path(), 120)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));

    assertThat(repository.saved().stationSequence()).isEqualTo(2);
    assertThat(repository.saved().state()).isEqualTo(SharedFolderRadioDocument.State.EMPTY);
    assertThat(repository.saved().path()).isNull();
    assertThat(repository.saved().startedAt()).isNull();
    assertThat(repository.saved().durationSeconds()).isNull();
    tracks.set(List.of(track));
    clock.advanceSeconds(5);

    SharedFolderRadioResponse restarted = service.current();

    assertThat(restarted.playback().stationSequence()).isEqualTo(3);
    assertThat(restarted.playback().startedAt()).isEqualTo(START.plusSeconds(15));
    assertThatThrownBy(() -> service.reportDuration(new SharedFolderRadioDurationRequest(
        initial.playback().stationSequence(), track.path(), 120)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void current_selectsOnlyTheAudioCatalogSnapshotAndStartsAtServerTime() {
    SharedDirectoryEntry track = track("Music/Live/song.flac");
    MutableClock clock = new MutableClock(START);
    InMemoryRadioRepository repository = new InMemoryRadioRepository();

    SharedFolderRadioResponse response = service(List.of(track), repository, clock, 0).current();

    assertThat(response.status()).isEqualTo(SharedFolderRadioResponse.Status.PLAYING);
    assertThat(response.playback().stationSequence()).isEqualTo(1);
    assertThat(response.playback().startedAt()).isEqualTo(START);
    assertThat(response.playback().positionSeconds()).isZero();
    assertThat(response.playback().durationSeconds()).isNull();
    assertThat(response.playback().entry()).isEqualTo(track);
  }

  @Test
  void current_afterServiceRestart_usesPersistedTrackSequenceAndStartTime() {
    SharedDirectoryEntry track = track("Music/song.mp3");
    MutableClock clock = new MutableClock(START);
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    service(List.of(track), repository, clock, 0).current();
    clock.advanceSeconds(12);

    SharedFolderRadioResponse restored = service(List.of(track), repository, clock, 0).current();

    assertThat(restored.playback().stationSequence()).isEqualTo(1);
    assertThat(restored.playback().startedAt()).isEqualTo(START);
    assertThat(restored.playback().positionSeconds()).isEqualTo(12.0);
  }

  @ParameterizedTest
  @MethodSource("invalidDurations")
  void reportDuration_rejectsNonFiniteAndOutOfBoundsSeconds(double durationSeconds) {
    SharedFolderRadioService service = service(
        List.of(track("Music/song.mp3")), new InMemoryRadioRepository(),
        new MutableClock(START), 0);

    assertThatThrownBy(() -> service.reportDuration(new SharedFolderRadioDurationRequest(
        1, "Music/song.mp3", durationSeconds)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
  }

  @Test
  void reportDuration_rejectsInvalidSequenceAndUnsafePathBeforeCatalogWork() {
    SharedFolderCatalogService catalog = mock(SharedFolderCatalogService.class);
    SharedFolderRadioService service = new SharedFolderRadioService(
        catalog, new InMemoryRadioRepository().repository(), new MutableClock(START), bound -> 0);

    assertThatThrownBy(() -> service.reportDuration(new SharedFolderRadioDurationRequest(
        0, "Music/song.mp3", 120)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    assertThatThrownBy(() -> service.reportDuration(new SharedFolderRadioDurationRequest(
        1, "../song.mp3", 120)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    org.mockito.Mockito.verifyNoInteractions(catalog);
  }

  @Test
  void responseConstructor_rejectsMismatchedStatusAndPlayback() {
    SharedFolderRadioResponse.Playback playback = new SharedFolderRadioResponse.Playback(
        1, START, 0, null, track("Music/song.mp3"));

    assertThatThrownBy(() -> new SharedFolderRadioResponse(
        SharedFolderRadioResponse.Status.EMPTY, playback))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderRadioResponse(
        SharedFolderRadioResponse.Status.PLAYING, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void documentConstructor_rejectsMismatchedPersistedState() {
    assertThatThrownBy(() -> new SharedFolderRadioDocument(
        SharedFolderRadioDocument.ID, SharedFolderRadioDocument.State.EMPTY,
        1, "Music/song.mp3", START, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderRadioDocument(
        SharedFolderRadioDocument.ID, SharedFolderRadioDocument.State.PLAYING,
        1, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);

    SharedFolderRadioDocument legacyPlaying = new SharedFolderRadioDocument(
        SharedFolderRadioDocument.ID, null, 1, "Music/song.mp3", START, null);
    assertThat(legacyPlaying.state()).isEqualTo(SharedFolderRadioDocument.State.PLAYING);
  }

  @ParameterizedTest
  @MethodSource("validDurationBounds")
  void reportDuration_acceptsTheInclusiveDurationBounds(double durationSeconds) {
    SharedDirectoryEntry track = track("Music/song.mp3");
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(
        List.of(track), repository, new MutableClock(START), 0);
    SharedFolderRadioResponse current = service.current();

    SharedFolderRadioResponse updated = service.reportDuration(new SharedFolderRadioDurationRequest(
        current.playback().stationSequence(), track.path(), durationSeconds));

    assertThat(updated.playback().durationSeconds()).isEqualTo(durationSeconds);
  }

  @Test
  void reportDuration_rejectsStaleSequenceAndPathWithConflict() {
    SharedDirectoryEntry track = track("Music/song.mp3");
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(
        List.of(track), repository, new MutableClock(START), 0);
    SharedFolderRadioResponse current = service.current();

    assertThatThrownBy(() -> service.reportDuration(new SharedFolderRadioDurationRequest(
        current.playback().stationSequence() + 1, track.path(), 120)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    assertThatThrownBy(() -> service.reportDuration(new SharedFolderRadioDurationRequest(
        current.playback().stationSequence(), "Music/other.mp3", 120)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void current_whenKnownTrackEndIsThreeSecondsOld_advancesAtThePriorEndAndAvoidsRepeat() {
    SharedDirectoryEntry first = track("Music/first.mp3");
    SharedDirectoryEntry second = track("Music/second.mp3");
    MutableClock clock = new MutableClock(START);
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(List.of(first, second), repository, clock, 0);
    SharedFolderRadioResponse initial = service.current();
    service.reportDuration(new SharedFolderRadioDurationRequest(
        initial.playback().stationSequence(), first.path(), 30));
    clock.advanceSeconds(33);

    SharedFolderRadioResponse advanced = service.current();

    assertThat(advanced.playback().entry()).isEqualTo(second);
    assertThat(advanced.playback().stationSequence()).isEqualTo(2);
    assertThat(advanced.playback().startedAt()).isEqualTo(START.plusSeconds(30));
    assertThat(advanced.playback().positionSeconds()).isEqualTo(3.0);
  }

  @Test
  void current_whenKnownTrackEndIsMoreThanThreeSecondsOld_preservesTheStationTimeline() {
    SharedDirectoryEntry first = track("Music/first.mp3");
    SharedDirectoryEntry second = track("Music/second.mp3");
    MutableClock clock = new MutableClock(START);
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(List.of(first, second), repository, clock, 0);
    SharedFolderRadioResponse initial = service.current();
    service.reportDuration(new SharedFolderRadioDurationRequest(
        initial.playback().stationSequence(), first.path(), 30));
    clock.advanceSeconds(34);

    SharedFolderRadioResponse advanced = service.current();

    assertThat(advanced.playback().entry()).isEqualTo(second);
    assertThat(advanced.playback().startedAt()).isEqualTo(START.plusSeconds(30));
    assertThat(advanced.playback().positionSeconds()).isEqualTo(4.0);
  }

  @Test
  void current_afterIdleTime_rollsAcrossPreviouslyObservedTrackDurations() {
    SharedDirectoryEntry first = track("Music/first.mp3");
    SharedDirectoryEntry second = track("Music/second.mp3");
    MutableClock clock = new MutableClock(START);
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(List.of(first, second), repository, clock, 0);
    SharedFolderRadioResponse firstPlayback = service.current();
    service.reportDuration(new SharedFolderRadioDurationRequest(
        firstPlayback.playback().stationSequence(), first.path(), 30));
    clock.advanceSeconds(31);
    SharedFolderRadioResponse secondPlayback = service.current();
    service.reportDuration(new SharedFolderRadioDurationRequest(
        secondPlayback.playback().stationSequence(), second.path(), 30));
    clock.advanceSeconds(69);

    SharedFolderRadioResponse caughtUp =
        service(List.of(first, second), repository, clock, 0).current();

    assertThat(caughtUp.playback().entry()).isEqualTo(second);
    assertThat(caughtUp.playback().stationSequence()).isEqualTo(4);
    assertThat(caughtUp.playback().startedAt()).isEqualTo(START.plusSeconds(90));
    assertThat(caughtUp.playback().positionSeconds()).isEqualTo(10.0);
    assertThat(caughtUp.playback().durationSeconds()).isEqualTo(30.0);
  }

  @Test
  void current_whenTrackRevisionChanges_doesNotReuseOrPersistTheOldDuration() {
    SharedDirectoryEntry original = track("Music/song.mp3", "original-revision");
    SharedDirectoryEntry replacement = track("Music/song.mp3", "replacement-revision");
    AtomicReference<List<SharedDirectoryEntry>> tracks =
        new AtomicReference<>(List.of(original));
    InMemoryRadioRepository repository = new InMemoryRadioRepository();
    SharedFolderRadioService service = service(
        tracks, repository, new MutableClock(START), 0);
    SharedFolderRadioResponse initial = service.current();
    service.reportDuration(new SharedFolderRadioDurationRequest(
        initial.playback().stationSequence(), original.path(), 30));
    tracks.set(List.of(replacement));

    SharedFolderRadioResponse refreshed = service.current();

    assertThat(refreshed.playback().durationSeconds()).isNull();
    assertThat(repository.saved().durationSeconds()).isNull();
  }

  @Test
  void current_obtainsCatalogSnapshotBeforeWaitingForStationTransitionLock() throws Exception {
    SharedDirectoryEntry track = track("Music/song.mp3");
    CountDownLatch firstRepositoryReadStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstRepositoryRead = new CountDownLatch(1);
    CountDownLatch bothCatalogSnapshotsObtained = new CountDownLatch(2);
    AtomicInteger repositoryReads = new AtomicInteger();
    SharedFolderCatalogService catalog = mock(SharedFolderCatalogService.class);
    when(catalog.audioTracksBelowMusic()).thenAnswer(ignored -> {
      bothCatalogSnapshotsObtained.countDown();
      return List.of(track);
    });
    SharedFolderRadioRepository repository = mock(SharedFolderRadioRepository.class);
    when(repository.findById(any())).thenAnswer(ignored -> {
      if (repositoryReads.getAndIncrement() == 0) {
        firstRepositoryReadStarted.countDown();
        if (!releaseFirstRepositoryRead.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to release the station repository");
        }
      }
      return Optional.empty();
    });
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    SharedFolderRadioService service = new SharedFolderRadioService(
        catalog, repository, new MutableClock(START), bound -> 0);
    var executor = Executors.newFixedThreadPool(2);

    try {
      var first = executor.submit(service::current);
      assertThat(firstRepositoryReadStarted.await(2, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(service::current);

      assertThat(bothCatalogSnapshotsObtained.await(2, TimeUnit.SECONDS)).isTrue();
      releaseFirstRepositoryRead.countDown();
      assertThat(first.get(2, TimeUnit.SECONDS).status())
          .isEqualTo(SharedFolderRadioResponse.Status.PLAYING);
      assertThat(second.get(2, TimeUnit.SECONDS).status())
          .isEqualTo(SharedFolderRadioResponse.Status.PLAYING);
    } finally {
      releaseFirstRepositoryRead.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void current_usesWallTimeAfterRepositoryReadWhenTrackExpiresWhileWaiting() throws Exception {
    SharedDirectoryEntry firstTrack = track("Music/first.mp3");
    SharedDirectoryEntry secondTrack = track("Music/second.mp3");
    CountDownLatch firstRepositoryReadStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstRepositoryRead = new CountDownLatch(1);
    CountDownLatch bothCatalogSnapshotsObtained = new CountDownLatch(2);
    AtomicInteger repositoryReads = new AtomicInteger();
    AtomicReference<SharedFolderRadioDocument> stored = new AtomicReference<>(
        new SharedFolderRadioDocument(
            SharedFolderRadioDocument.ID, SharedFolderRadioDocument.State.PLAYING,
            1, firstTrack.path(), START, 30.0,
            List.of(new SharedFolderRadioDocument.TrackDuration(
                firstTrack.path(), firstTrack.observedToken(), 30.0))));
    ThreadLocal<Boolean> repositoryReadCompleted =
        ThreadLocal.withInitial(() -> Boolean.FALSE);
    SharedFolderCatalogService catalog = mock(SharedFolderCatalogService.class);
    when(catalog.audioTracksBelowMusic()).thenAnswer(ignored -> {
      bothCatalogSnapshotsObtained.countDown();
      return List.of(firstTrack, secondTrack);
    });
    SharedFolderRadioRepository repository = mock(SharedFolderRadioRepository.class);
    when(repository.findById(any())).thenAnswer(ignored -> {
      if (repositoryReads.getAndIncrement() == 0) {
        firstRepositoryReadStarted.countDown();
        if (!releaseFirstRepositoryRead.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out waiting to release the station repository");
        }
      }
      repositoryReadCompleted.set(Boolean.TRUE);
      return Optional.of(stored.get());
    });
    when(repository.save(any())).thenAnswer(invocation -> {
      SharedFolderRadioDocument document = invocation.getArgument(0);
      stored.set(document);
      return document;
    });
    Clock transitionClock = new Clock() {
      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }

      @Override
      public Instant instant() {
        return repositoryReadCompleted.get() ? START.plusSeconds(34) : START;
      }
    };
    SharedFolderRadioService service = new SharedFolderRadioService(
        catalog, repository, transitionClock, bound -> 0);
    var executor = Executors.newFixedThreadPool(2);

    try {
      var first = executor.submit(service::current);
      assertThat(firstRepositoryReadStarted.await(2, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(service::current);
      assertThat(bothCatalogSnapshotsObtained.await(2, TimeUnit.SECONDS)).isTrue();
      releaseFirstRepositoryRead.countDown();

      assertThat(first.get(2, TimeUnit.SECONDS).playback().stationSequence()).isEqualTo(2);
      SharedFolderRadioResponse waitingRequest = second.get(2, TimeUnit.SECONDS);
      assertThat(waitingRequest.playback().stationSequence()).isEqualTo(2);
      assertThat(waitingRequest.playback().entry()).isEqualTo(secondTrack);
      assertThat(waitingRequest.playback().startedAt()).isEqualTo(START.plusSeconds(30));
      assertThat(waitingRequest.playback().positionSeconds()).isEqualTo(4.0);
    } finally {
      releaseFirstRepositoryRead.countDown();
      executor.shutdownNow();
      repositoryReadCompleted.remove();
    }
  }

  private SharedFolderRadioService service(
      List<SharedDirectoryEntry> tracks,
      InMemoryRadioRepository repository,
      Clock clock,
      int selectedIndex) {
    SharedFolderCatalogService catalog = mock(SharedFolderCatalogService.class);
    when(catalog.audioTracksBelowMusic()).thenReturn(List.copyOf(tracks));
    IntUnaryOperator randomIndex = bound -> selectedIndex;
    return new SharedFolderRadioService(catalog, repository.repository(), clock, randomIndex);
  }

  private SharedFolderRadioService service(
      AtomicReference<List<SharedDirectoryEntry>> tracks,
      InMemoryRadioRepository repository,
      Clock clock,
      int selectedIndex) {
    SharedFolderCatalogService catalog = mock(SharedFolderCatalogService.class);
    when(catalog.audioTracksBelowMusic()).thenAnswer(ignored -> List.copyOf(tracks.get()));
    IntUnaryOperator randomIndex = bound -> selectedIndex;
    return new SharedFolderRadioService(catalog, repository.repository(), clock, randomIndex);
  }

  private SharedDirectoryEntry track(String path) {
    return track(path, "observed-" + path);
  }

  private SharedDirectoryEntry track(String path, String observedToken) {
    return new SharedDirectoryEntry(
        path.substring(path.lastIndexOf('/') + 1), path, SharedDirectoryEntryType.FILE, 128,
        START.minusSeconds(60), SharedFolderPreviewKind.AUDIO, observedToken);
  }

  private static Stream<Arguments> invalidDurations() {
    return Stream.of(
        Arguments.of(Double.NaN),
        Arguments.of(Double.NEGATIVE_INFINITY),
        Arguments.of(Double.POSITIVE_INFINITY),
        Arguments.of(0.999),
        Arguments.of(86_400.001));
  }

  private static Stream<Arguments> validDurationBounds() {
    return Stream.of(Arguments.of(1.0), Arguments.of(86_400.0));
  }

  private static final class InMemoryRadioRepository {
    private final AtomicReference<SharedFolderRadioDocument> stored = new AtomicReference<>();
    private final SharedFolderRadioRepository repository = mock(SharedFolderRadioRepository.class);

    private InMemoryRadioRepository() {
      when(repository.findById(any())).thenAnswer(ignored -> Optional.ofNullable(stored.get()));
      when(repository.save(any())).thenAnswer(invocation -> {
        SharedFolderRadioDocument document = invocation.getArgument(0);
        stored.set(document);
        return document;
      });
    }

    private SharedFolderRadioRepository repository() {
      return repository;
    }

    private SharedFolderRadioDocument saved() {
      return stored.get();
    }
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }

    private void advanceSeconds(long seconds) {
      current = current.plusSeconds(seconds);
    }
  }
}

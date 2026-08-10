package dev.christopherbell.music.radio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.security.MusicAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MusicQueueServiceTest {

  @Test
  void writerAddsToTheSingleGlobalQueueAtTheExactVersion() {
    var runtimeState = mock(MusicRuntimeStateStore.class);
    var catalog = mock(MusicCatalog.class);
    var access = mock(MusicAccessService.class);
    MusicTrack track = track("song.mp3");
    when(access.requireWrite()).thenReturn(Account.builder().id("writer-1").build());
    when(catalog.findReady(track.id())).thenReturn(Optional.of(track));
    when(runtimeState.findQueue()).thenReturn(Optional.empty());
    when(runtimeState.saveQueue(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
      MusicQueueState state = invocation.getArgument(0);
      return new MusicQueueState(state.id(), state.entries(), 0L);
    });
    var service = new MusicQueueService(
        runtimeState, catalog, access,
        Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC));

    MusicQueueView result = service.add(track.id(), 0);

    assertThat(result.version()).isEqualTo(1);
    assertThat(result.items()).singleElement().satisfies(item -> {
      assertThat(item.track().id()).isEqualTo(track.id());
      assertThat(item.enqueuedByAccountId()).isEqualTo("writer-1");
    });
  }

  @Test
  void staleWriterCannotOverwriteTheGlobalQueue() {
    var runtimeState = mock(MusicRuntimeStateStore.class);
    when(runtimeState.findQueue())
        .thenReturn(Optional.of(new MusicQueueState(MusicQueueState.ID, java.util.List.of(), 3L)));
    var access = mock(MusicAccessService.class);
    when(access.requireWrite()).thenReturn(Account.builder().id("writer-1").build());
    var service = new MusicQueueService(
        runtimeState, mock(MusicCatalog.class), access, Clock.systemUTC());

    assertThatThrownBy(() -> service.remove("missing", 3))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");
  }

  @Test
  void optimisticQueueSaveContentionMapsToConflict() {
    assertSaveContentionMapsToConflict(new OptimisticLockingFailureException("stale queue"));
  }

  @Test
  void duplicateQueueSaveContentionMapsToConflict() {
    assertSaveContentionMapsToConflict(new DuplicateKeyException("duplicate queue"));
  }

  private void assertSaveContentionMapsToConflict(RuntimeException failure) {
    MusicTrack track = track("song.mp3");
    var runtimeState = mock(MusicRuntimeStateStore.class);
    when(runtimeState.findQueue()).thenReturn(Optional.empty());
    when(runtimeState.saveQueue(org.mockito.ArgumentMatchers.any())).thenThrow(failure);
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(track.id())).thenReturn(Optional.of(track));
    var access = mock(MusicAccessService.class);
    when(access.requireWrite()).thenReturn(Account.builder().id("writer-1").build());
    var service = new MusicQueueService(runtimeState, catalog, access, Clock.systemUTC());

    assertThatThrownBy(() -> service.add(track.id(), 0))
        .isInstanceOfSatisfying(ResponseStatusException.class, contention -> {
          assertThat(contention.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(contention.getReason()).isEqualTo("Music queue changed. Refresh and retry.");
        });
  }

  private MusicTrack track(String path) {
    return MusicTrack.ready(path, "token", new MusicProbeResult(
        "Song", "Artist", "Artist", "Album", 1, 1, "Genre", 2026,
        10, "mp3", "mp3", false), null, Instant.EPOCH);
  }
}

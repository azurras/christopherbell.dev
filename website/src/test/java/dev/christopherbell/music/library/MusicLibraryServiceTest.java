package dev.christopherbell.music.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.music.catalog.MusicCatalog;
import dev.christopherbell.music.catalog.MusicProbeResult;
import dev.christopherbell.music.catalog.MusicTrack;
import dev.christopherbell.music.radio.MusicRadioHistoryRepository;
import dev.christopherbell.music.security.MusicAccessService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

class MusicLibraryServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

  @Test
  void listenerReadsOneBoundedGlobalPlaylistList() {
    var mongo = mock(MongoTemplate.class);
    var query = ArgumentCaptor.forClass(Query.class);
    when(mongo.find(query.capture(), eq(MusicPlaylist.class))).thenReturn(List.of());
    var access = mock(MusicAccessService.class);
    var service = service(mock(MusicPlaylistRepository.class), mock(MusicCatalog.class),
        mock(MusicRadioHistoryRepository.class), access, mongo);

    assertThat(service.playlists()).isEmpty();

    verify(access).requireRead();
    assertThat(query.getValue().getLimit()).isEqualTo(100);
    assertThat(query.getValue().getSortObject().get("normalizedName")).isEqualTo(1);
  }

  @Test
  void listenerResolvesOnePlaylistTrackSetForServerSideCatalogPaging() {
    var repository = mock(MusicPlaylistRepository.class);
    when(repository.findById("playlist-1")).thenReturn(Optional.of(new MusicPlaylist(
        "playlist-1", "road-trip", "Road Trip", List.of("track-a", "track-b"),
        3L, "writer", NOW)));
    var access = mock(MusicAccessService.class);
    var service = service(repository, mock(MusicCatalog.class),
        mock(MusicRadioHistoryRepository.class), access, mock(MongoTemplate.class));

    assertThat(service.playlistTrackIds("playlist-1"))
        .containsExactly("track-a", "track-b");
    verify(access).requireRead();
  }

  @Test
  void writerCreatesAGlobalPlaylistWithValidatedTracksAndPublicVersion() {
    MusicTrack song = track("song.mp3", false, false);
    var repository = mock(MusicPlaylistRepository.class);
    when(repository.save(any())).thenAnswer(invocation -> {
      MusicPlaylist value = invocation.getArgument(0);
      return new MusicPlaylist(
          value.id(), value.normalizedName(), value.name(), value.trackIds(), 0L,
          value.updatedByAccountId(), value.updatedAt());
    });
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(song.id())).thenReturn(Optional.of(song));
    var access = mock(MusicAccessService.class);
    when(access.requireWrite()).thenReturn(Account.builder().id("writer-1").build());
    var mongo = mock(MongoTemplate.class);
    when(mongo.count(any(Query.class), eq(MusicPlaylist.class))).thenReturn(0L);
    var service = service(repository, catalog, mock(MusicRadioHistoryRepository.class), access, mongo);

    MusicPlaylistView result = service.create("  Road Trip  ", List.of(song.id()));

    assertThat(result.name()).isEqualTo("Road Trip");
    assertThat(result.trackIds()).containsExactly(song.id());
    assertThat(result.version()).isEqualTo(1);
    assertThat(result.updatedByAccountId()).isEqualTo("writer-1");
  }

  @Test
  void stalePlaylistWriterCannotOverwriteNewerGlobalState() {
    var repository = mock(MusicPlaylistRepository.class);
    when(repository.findById("playlist-1")).thenReturn(Optional.of(new MusicPlaylist(
        "playlist-1", "name", "Name", List.of(), 2L, "writer", NOW)));
    var access = mock(MusicAccessService.class);
    when(access.requireWrite()).thenReturn(Account.builder().id("writer-2").build());
    var service = service(repository, mock(MusicCatalog.class),
        mock(MusicRadioHistoryRepository.class), access, mock(MongoTemplate.class));

    assertThatThrownBy(() -> service.update("playlist-1", 2, "Other", List.of()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");
  }

  @Test
  void writerPreferenceMutationMatchesTheExactSharedState() {
    MusicTrack before = track("song.mp3", false, false);
    MusicTrack after = track("song.mp3", true, true);
    var catalog = mock(MusicCatalog.class);
    when(catalog.findReady(before.id())).thenReturn(Optional.of(before), Optional.of(after));
    var mongo = mock(MongoTemplate.class);
    when(mongo.updateFirst(any(Query.class), any(Update.class), eq(MusicTrack.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    var access = mock(MusicAccessService.class);
    var service = service(mock(MusicPlaylistRepository.class), catalog,
        mock(MusicRadioHistoryRepository.class), access, mongo);

    var result = service.updatePreferences(before.id(), false, false, true, true);

    assertThat(result.favorite()).isTrue();
    assertThat(result.excludedFromRadio()).isTrue();
    verify(access).requireWrite();
    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).updateFirst(query.capture(), any(Update.class), eq(MusicTrack.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("favorite=false", "excludedFromRadio=false");
  }

  private MusicLibraryService service(
      MusicPlaylistRepository playlists,
      MusicCatalog catalog,
      MusicRadioHistoryRepository history,
      MusicAccessService access,
      MongoTemplate mongo) {
    return new MusicLibraryService(
        playlists, catalog, history, access, mongo, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private MusicTrack track(String path, boolean favorite, boolean excluded) {
    MusicTrack initial = MusicTrack.ready(path, "token", new MusicProbeResult(
        "Song", "Artist", "Artist", "Album", 1, 1, "Genre", 2026,
        180, "mp3", "mp3", false), null, NOW);
    return new MusicTrack(
        initial.id(), initial.path(), initial.observedToken(), initial.pendingObservedToken(),
        initial.title(), initial.artist(), initial.albumArtist(), initial.album(),
        initial.trackNumber(), initial.discNumber(), initial.genre(), initial.year(),
        initial.durationSeconds(), initial.audioCodec(), initial.container(),
        initial.artworkRevision(), favorite, excluded, initial.indexStatus(), initial.indexFailure(),
        initial.lastProbeAttemptAt(), initial.indexedAt(), initial.missingSince());
  }
}

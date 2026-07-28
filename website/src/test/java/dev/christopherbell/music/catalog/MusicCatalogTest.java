package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class MusicCatalogTest {

  @Test
  void searchReturnsRequestedStablePageAndFullMatchingCount() {
    var mongo = mock(MongoTemplate.class);
    var repository = mock(MusicTrackRepository.class);
    var query = ArgumentCaptor.forClass(Query.class);
    when(mongo.count(any(Query.class), eq(MusicTrack.class))).thenReturn(1_549L);
    when(mongo.find(query.capture(), eq(MusicTrack.class))).thenReturn(List.of(
        track("a.mp3", "Song A", "Artist", "Album", "Rock", 2025),
        track("b.mp3", "Song B", "Artist", "Album", "Rock", 2026)));
    when(mongo.findDistinct(
        any(Query.class), eq("artist"), eq(MusicTrack.class), eq(String.class)))
        .thenReturn(List.of("Artist"));
    when(mongo.findDistinct(
        any(Query.class), eq("album"), eq(MusicTrack.class), eq(String.class)))
        .thenReturn(List.of("Album"));
    when(mongo.findDistinct(
        any(Query.class), eq("genre"), eq(MusicTrack.class), eq(String.class)))
        .thenReturn(List.of("Rock"));
    when(mongo.findDistinct(
        any(Query.class), eq("year"), eq(MusicTrack.class), eq(Integer.class)))
        .thenReturn(List.of(2025, 2026));
    var catalog = new MusicCatalog(mongo, repository);

    var result = catalog.search(new MusicQuery(
        ".*", null, null, null, null, null, 1, 50));

    assertThat(query.getValue().getLimit()).isEqualTo(50);
    assertThat(query.getValue().getSkip()).isEqualTo(50);
    assertThat(query.getValue().getSortObject().get("id")).isEqualTo(1);
    assertThat(query.getValue().getQueryObject().toString()).contains("\\Q.*\\E");
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(50);
    assertThat(result.totalTracks()).isEqualTo(1_549);
    assertThat(result.totalPages()).isEqualTo(31);
    assertThat(result.tracks()).hasSize(2);
    assertThat(result.albums()).singleElement().satisfies(album -> {
      assertThat(album.albumArtist()).isEqualTo("Artist");
      assertThat(album.album()).isEqualTo("Album");
      assertThat(album.tracks()).hasSize(2);
    });
    assertThat(result.facets().artists()).containsExactly("Artist");
    assertThat(result.facets().years()).containsExactly(2025, 2026);
  }

  @Test
  void searchClampsAStalePageToTheLastAvailablePage() {
    var mongo = mock(MongoTemplate.class);
    var query = ArgumentCaptor.forClass(Query.class);
    when(mongo.count(any(Query.class), eq(MusicTrack.class))).thenReturn(51L);
    when(mongo.find(query.capture(), eq(MusicTrack.class))).thenReturn(List.of(
        track("last.mp3", "Last Song", "Artist", "Album", "Rock", 2026)));
    var catalog = new MusicCatalog(mongo, mock(MusicTrackRepository.class));

    var result = catalog.search(new MusicQuery(
        null, null, null, null, null, null, 99, 50));

    assertThat(result.page()).isEqualTo(1);
    assertThat(result.totalPages()).isEqualTo(2);
    assertThat(query.getValue().getSkip()).isEqualTo(50);
  }

  @Test
  void searchAppliesFavoriteAndPlaylistConstraintsBeforeCounting() {
    var mongo = mock(MongoTemplate.class);
    var countQuery = ArgumentCaptor.forClass(Query.class);
    when(mongo.count(countQuery.capture(), eq(MusicTrack.class))).thenReturn(0L);
    when(mongo.find(any(Query.class), eq(MusicTrack.class))).thenReturn(List.of());
    var catalog = new MusicCatalog(mongo, mock(MusicTrackRepository.class));

    catalog.search(new MusicQuery(
        null, null, null, null, true, List.of("track-a", "track-b"), 0, 50));

    assertThat(countQuery.getValue().getQueryObject().toString())
        .contains("favorite=true", "$in=[track-a, track-b]");
  }

  @Test
  void searchFacetsDescribeTheFullFilteredResultInsteadOfOnlyTheCurrentPage() {
    var mongo = mock(MongoTemplate.class);
    when(mongo.count(any(Query.class), eq(MusicTrack.class))).thenReturn(1_549L);
    when(mongo.find(any(Query.class), eq(MusicTrack.class))).thenReturn(List.of(
        track("page.mp3", "Page Song", "Page Artist", "Page Album", "Page Genre", 2026)));
    when(mongo.findDistinct(
        any(Query.class), eq("artist"), eq(MusicTrack.class), eq(String.class)))
        .thenReturn(List.of("Another Artist", "Page Artist"));
    when(mongo.findDistinct(
        any(Query.class), eq("album"), eq(MusicTrack.class), eq(String.class)))
        .thenReturn(List.of("Another Album", "Page Album"));
    when(mongo.findDistinct(
        any(Query.class), eq("genre"), eq(MusicTrack.class), eq(String.class)))
        .thenReturn(List.of("Another Genre", "Page Genre"));
    when(mongo.findDistinct(
        any(Query.class), eq("year"), eq(MusicTrack.class), eq(Integer.class)))
        .thenReturn(List.of(2025, 2026));
    var catalog = new MusicCatalog(mongo, mock(MusicTrackRepository.class));

    var result = catalog.search(new MusicQuery(
        null, null, null, null, null, null, 0, 50));

    assertThat(result.facets().artists()).containsExactly("Another Artist", "Page Artist");
    assertThat(result.facets().albums()).containsExactly("Another Album", "Page Album");
    assertThat(result.facets().genres()).containsExactly("Another Genre", "Page Genre");
    assertThat(result.facets().years()).containsExactly(2025, 2026);
  }

  private MusicTrack track(
      String path,
      String title,
      String artist,
      String album,
      String genre,
      int year) {
    return MusicTrack.ready(
        path,
        "token-" + path,
        new MusicProbeResult(
            title, artist, artist, album, 1, 1, genre, year,
            180, "mp3", "mp3", false),
        null,
        Instant.parse("2026-07-28T12:00:00Z"));
  }
}

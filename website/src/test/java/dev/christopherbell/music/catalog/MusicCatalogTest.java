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
  void searchQuotesUserTextCapsResultsAndReturnsSharedAlbumGroupsAndFacets() {
    var mongo = mock(MongoTemplate.class);
    var repository = mock(MusicTrackRepository.class);
    var query = ArgumentCaptor.forClass(Query.class);
    when(mongo.find(query.capture(), eq(MusicTrack.class))).thenReturn(List.of(
        track("a.mp3", "Song A", "Artist", "Album", "Rock", 2025),
        track("b.mp3", "Song B", "Artist", "Album", "Rock", 2026)));
    var catalog = new MusicCatalog(mongo, repository);

    var result = catalog.search(new MusicQuery(".*", null, null, null, 500));

    assertThat(query.getValue().getLimit()).isEqualTo(100);
    assertThat(query.getValue().getQueryObject().toString()).contains("\\Q.*\\E");
    assertThat(result.tracks()).hasSize(2);
    assertThat(result.albums()).singleElement().satisfies(album -> {
      assertThat(album.albumArtist()).isEqualTo("Artist");
      assertThat(album.album()).isEqualTo("Album");
      assertThat(album.tracks()).hasSize(2);
    });
    assertThat(result.facets().artists()).containsExactly("Artist");
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

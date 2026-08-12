package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Query;

class MusicCatalogTest {

  @Test
  void searchReturnsRequestedStablePageAndFullMatchingCount() {
    var boundary = boundary();
    var query = ArgumentCaptor.forClass(Query.class);
    when(boundary.operations.count(any(Query.class))).thenReturn(1_549L);
    when(boundary.operations.find(query.capture(), any(Pageable.class))).thenReturn(List.of(
        track("a.mp3", "Song A", "Artist", "Album", "Rock", 2025),
        track("b.mp3", "Song B", "Artist", "Album", "Rock", 2026)));
    when(boundary.operations.aggregate(any(KindScopedAggregation.class),
        org.mockito.ArgumentMatchers.eq(Document.class)))
        .thenReturn(List.of(new Document("_id", "Artist")))
        .thenReturn(List.of(new Document("_id", "Album")))
        .thenReturn(List.of(new Document("_id", "Rock")))
        .thenReturn(List.of(new Document("_id", 2025), new Document("_id", 2026)));
    var catalog = new MusicCatalog(boundary.factory, mock(MusicTrackRepository.class));

    var result = catalog.search(new MusicQuery(
        ".*", null, null, null, null, null, 1, 50));

    assertThat(query.getValue().getLimit()).isEqualTo(50);
    assertThat(query.getValue().getSkip()).isEqualTo(50);
    assertThat(query.getValue().getSortObject().get("id")).isEqualTo(1);
    assertThat(query.getValue().getQueryObject().toString()).contains("\\Q.*\\E");
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.totalTracks()).isEqualTo(1_549);
    assertThat(result.totalPages()).isEqualTo(31);
    assertThat(result.tracks()).hasSize(2);
    assertThat(result.facets().artists()).containsExactly("Artist");
    assertThat(result.facets().years()).containsExactly(2025, 2026);
  }

  @Test
  void searchClampsAStalePageAndAppliesFiltersBeforeCounting() {
    var boundary = boundary();
    var countQuery = ArgumentCaptor.forClass(Query.class);
    var pageQuery = ArgumentCaptor.forClass(Query.class);
    when(boundary.operations.count(countQuery.capture())).thenReturn(51L);
    when(boundary.operations.find(pageQuery.capture(), any(Pageable.class))).thenReturn(List.of(
        track("last.mp3", "Last Song", "Artist", "Album", "Rock", 2026)));
    when(boundary.operations.aggregate(any(), org.mockito.ArgumentMatchers.eq(Document.class)))
        .thenReturn(List.of());
    var catalog = new MusicCatalog(boundary.factory, mock(MusicTrackRepository.class));

    var result = catalog.search(new MusicQuery(
        null, null, null, null, true, List.of("track-a", "track-b"), 99, 50));

    assertThat(result.page()).isEqualTo(1);
    assertThat(pageQuery.getValue().getSkip()).isEqualTo(50);
    assertThat(countQuery.getValue().getQueryObject().toString())
        .contains("favorite=true", "$in=[track-a, track-b]");
  }

  @SuppressWarnings("unchecked")
  private static Boundary boundary() {
    var factory = mock(DomainMongoOperationsFactory.class);
    var operations = mock(KindScopedMongoOperations.class);
    when(factory.forType(MusicTrack.class)).thenReturn(operations);
    return new Boundary(factory, operations);
  }

  private MusicTrack track(
      String path, String title, String artist, String album, String genre, int year) {
    return MusicTrack.ready(
        path,
        "token-" + path,
        new MusicProbeResult(
            title, artist, artist, album, 1, 1, genre, year,
            180, "mp3", "mp3", false),
        null,
        Instant.parse("2026-07-28T12:00:00Z"));
  }

  private record Boundary(
      DomainMongoOperationsFactory factory,
      KindScopedMongoOperations<MusicTrack> operations) {}
}

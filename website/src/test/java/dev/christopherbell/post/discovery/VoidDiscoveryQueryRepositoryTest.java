package dev.christopherbell.post.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.pagination.StableCursor;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class VoidDiscoveryQueryRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");

  @Mock private MongoTemplate mongo;
  private StableCursorCodec cursors;
  private VoidDiscoveryQueryRepository repository;

  @BeforeEach
  void setUp() {
    cursors = new StableCursorCodec();
    repository = new VoidDiscoveryQueryRepository(mongo, cursors);
  }

  @Test
  void newArrivalsAreActiveRootsWithStableDescendingPagination() throws Exception {
    var boundary = post("p2", NOW.minusSeconds(20));
    var extra = post("p1", NOW.minusSeconds(30));
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of(boundary, extra));

    var page = repository.newArrivals(
        Optional.of(new StableCursor(NOW.minusSeconds(10), "p3")), 1, NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("parentId", "expiresOn", "$gt", "createdOn", "$lt", "_id");
    assertThat(query.getValue().getSortObject().toString())
        .contains("createdOn=-1", "_id=-1");
    assertThat(page.items()).containsExactly(boundary);
    assertThat(cursors.decode(page.nextCursor()))
        .contains(new StableCursor(boundary.getCreatedOn(), boundary.getId()));
  }

  @Test
  void fadingSoonClampsTheFetchAndUsesAscendingExpirationOrder() {
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of());

    repository.fadingSoon(Optional.empty(), 500, NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
    assertThat(query.getValue().getLimit()).isEqualTo(25);
    assertThat(query.getValue().getSortObject().toString())
        .contains("expiresOn=1", "_id=1");
  }

  @Test
  void revivedRequiresAConfirmedExtensionTimestamp() {
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of());

    repository.recentlyRevived(Optional.empty(), 12, NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("lastExtendedOn", "$ne", "expiresOn", "$gt");
    assertThat(query.getValue().getSortObject().toString())
        .contains("lastExtendedOn=-1", "_id=-1");
  }

  @Test
  void topicThreadsAreGroupedToActiveRootsBeforePaging() {
    when(mongo.aggregate(any(Aggregation.class), eq("posts"), eq(Post.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    repository.topic("music", Optional.empty(), 24, NOW);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("posts"), eq(Post.class));
    var pipeline = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
    assertThat(pipeline)
        .contains("topics.canonical", "music", "$group", "$lookup", "$replaceRoot", "$limit=25");
  }

  @Test
  void topicSummariesUseActivityAndCanonicalAsStableKeys() {
    when(mongo.aggregate(any(Aggregation.class), eq("posts"), eq(VoidTopicSummary.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    repository.topics(Optional.of(new StableCursor(NOW.minusSeconds(1), "music")), 12, NOW);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("posts"), eq(VoidTopicSummary.class));
    var pipeline = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
    assertThat(pipeline)
        .contains("$unwind", "$lookup", "root.expiresOn", "$group", "activityOn", "canonical", "$limit=13");
  }

  private static Post post(String id, Instant createdOn) {
    return Post.builder()
        .id(id)
        .rootId(id)
        .accountId("account")
        .createdOn(createdOn)
        .expiresOn(NOW.plusSeconds(60))
        .build();
  }
}

package dev.christopherbell.post.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.pagination.StableCursor;
import dev.christopherbell.pagination.StableCursorCodec;
import dev.christopherbell.post.model.Post;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
class PostFeedQueryRepositoryTest {
  @Mock private MongoTemplate mongo;
  private StableCursorCodec cursorCodec;
  private PostFeedQueryRepository repository;

  @BeforeEach
  void setUp() {
    cursorCodec = new StableCursorCodec();
    repository = new PostFeedQueryRepository(mongo, cursorCodec);
  }

  @Test
  @DisplayName("Global feed pages include timestamp and id in the descending boundary")
  void global_usesStableCursorAndReturnsNextCursor() throws Exception {
    var timestamp = Instant.parse("2026-07-26T12:00:00Z");
    var boundary = post("p2", timestamp);
    var extra = post("p1", timestamp.minusSeconds(1));
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of(boundary, extra));

    var result = repository.global(Optional.of(new StableCursor(timestamp, "p3")), 1);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("createdOn", "$lt", "_id");
    assertThat(query.getValue().getSortObject().toString())
        .contains("createdOn=-1", "_id=-1");
    assertThat(result.posts()).containsExactly(boundary);
    assertThat(cursorCodec.decode(result.nextCursor()))
        .contains(new StableCursor(timestamp, "p2"));
  }

  @Test
  @DisplayName("Author and following pages retain their account scope with stable boundaries")
  void scopedPages_includeAccountCriteria() {
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of());

    repository.account("account-1", Optional.empty(), 20);
    repository.accounts(List.of("account-1", "account-2"), Optional.empty(), 20);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo, org.mockito.Mockito.times(2)).find(query.capture(), eq(Post.class));
    assertThat(query.getAllValues().get(0).getQueryObject().toString())
        .contains("accountId=account-1");
    assertThat(query.getAllValues().get(1).getQueryObject().toString())
        .contains("accountId", "$in", "account-2");
  }

  @Test
  @DisplayName("Visibility predicates are applied before the stable page limit")
  void global_appliesVisibilityInsideMongoQuery() {
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of());
    var cutoff = Instant.parse("2026-07-29T04:00:00Z");

    repository.global(
        Optional.empty(),
        20,
        new PostFeedVisibility(
            Set.of("muted-account"), Set.of("hidden-root"), Optional.of(cutoff)));

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("expiresOn", cutoff.toString(), "accountId", "muted-account", "rootId", "hidden-root");
    assertThat(query.getValue().getLimit()).isEqualTo(21);
  }

  @Test
  @DisplayName("Following pages join unique edges without materializing an account id list")
  void following_usesEdgeLookupBeforeLimit() {
    when(mongo.aggregate(any(Aggregation.class), eq("posts"), eq(Post.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    repository.following(
        "self", Optional.empty(), 20, PostFeedVisibility.unrestricted());

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("posts"), eq(Post.class));
    var pipeline = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
    assertThat(pipeline)
        .contains("$lookup", "account_follows", "followerAccountId", "followedAccountId", "$limit=21")
        .doesNotContain("$in");
  }

  private Post post(String id, Instant createdOn) {
    return Post.builder().id(id).accountId("account-1").text(id).createdOn(createdOn).build();
  }
}

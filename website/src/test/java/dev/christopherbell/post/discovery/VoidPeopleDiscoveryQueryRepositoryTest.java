package dev.christopherbell.post.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostTopic;
import dev.christopherbell.post.like.PostLikeStore;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
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
class VoidPeopleDiscoveryQueryRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");
  @Mock private MongoTemplate mongo;
  @Mock private PostLikeStore likes;

  @Test
  void interestsUseOnlyBoundedActiveParticipationAndKeepAlives() {
    var post = Post.builder()
        .topics(List.of(new PostTopic("music", "Music"), new PostTopic("music", "MUSIC")))
        .build();
    when(mongo.find(any(Query.class), eq(Post.class))).thenReturn(List.of(post));

    when(likes.recentLikedPostIds("self")).thenReturn(List.of("liked-post"));
    var interests = new VoidPeopleDiscoveryQueryRepository(mongo, likes).interestsFor("self", NOW);

    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Post.class));
    assertThat(query.getValue().getLimit()).isEqualTo(256);
    assertThat(query.getValue().getQueryObject().toString())
        .contains("expiresOn", "accountId", "liked-post", "self")
        .doesNotContain("likedBy");
    assertThat(interests).containsExactly("music");
  }

  @Test
  void candidatePoolIsCappedAndUsesRecentActivityWithoutEngagementTotals() {
    when(mongo.aggregate(any(Aggregation.class), eq("posts"), eq(VoidPersonCandidate.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    new VoidPeopleDiscoveryQueryRepository(mongo, likes).recentActiveCandidates(NOW, 500);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("posts"), eq(VoidPersonCandidate.class));
    var pipeline = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
    assertThat(pipeline)
        .contains("expiresOn", "lastExtendedOn", "createdOn", "$group", "$limit=128")
        .doesNotContain("likesCount", "threadReplyLikesCount", "follower");
  }
}

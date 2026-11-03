package dev.christopherbell.post.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.post.like.PostLikeStore;
import dev.christopherbell.post.model.Post;
import dev.christopherbell.post.model.PostTopic;
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

@ExtendWith(MockitoExtension.class)
class VoidPeopleDiscoveryQueryRepositoryTest {
  private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");
  @Mock private MongoTemplate mongo;
  @Mock private PostLikeStore likes;

  @Test
  void interestsUseOnlyBoundedActiveParticipationAndKeepAlives() {
    var post = Post.builder()
        .id("post-1")
        .topics(List.of(new PostTopic("music", "Music"), new PostTopic("music", "MUSIC")))
        .build();
    when(likes.recentLikedPostIds("self")).thenReturn(List.of("liked-post"));
    var factory = DomainMongoOperationsTestFactory
        .create(mongo);
    var postPayload = new Document("_id", "post-1").append("topics", List.of(
        new Document("canonical", "music").append("display", "Music")));
    when(mongo.aggregate(any(Aggregation.class), eq("content"), eq(Document.class)))
        .thenReturn(new AggregationResults<>(List.of(postPayload), new Document()));
    var interests = new VoidPeopleDiscoveryQueryRepository(factory, likes).interestsFor("self", NOW);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("content"), eq(Document.class));
    var pipeline = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
    assertThat(pipeline).contains("_kind=post", "expirationRoot.payload.expiresOn", "payload.accountId",
        "liked-post", "self", "$limit=256").doesNotContain("likedBy");
    assertThat(pipeline.indexOf("expirationRoot.payload.expiresOn")).isLessThan(pipeline.lastIndexOf("$limit=256"));
    assertThat(interests).containsExactly("music");
  }

  @Test
  void candidatePoolIsCappedAndUsesRecentActivityWithoutEngagementTotals() {
    when(mongo.aggregate(any(Aggregation.class), eq("content"), eq(Document.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    new VoidPeopleDiscoveryQueryRepository(
        DomainMongoOperationsTestFactory.create(mongo),
        likes).recentActiveCandidates(NOW, 500);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("content"), eq(Document.class));
    var pipeline = aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT).toString();
    assertThat(pipeline)
        .contains("_kind=post", "expiresOn", "lastExtendedOn", "createdOn", "$group", "$limit=128")
        .doesNotContain("likesCount", "threadReplyLikesCount", "follower");
  }
}

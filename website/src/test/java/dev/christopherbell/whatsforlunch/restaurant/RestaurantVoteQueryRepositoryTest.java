package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteSummary;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
class RestaurantVoteQueryRepositoryTest {
  @Mock private MongoTemplate mongo;
  private RestaurantVoteQueryRepository repository;

  @BeforeEach
  void setUp() {
    repository = new RestaurantVoteQueryRepository(mongo);
  }

  @Test
  void topLikedGroupsVotesOrdersTiesAndBoundsTheMongoResult() {
    var summary = new RestaurantVoteSummary("restaurant-1", 2, 1, 3);
    when(mongo.aggregate(any(Aggregation.class), eq("whatsforlunch_ratings"),
        eq(RestaurantVoteSummary.class)))
        .thenReturn(new AggregationResults<>(List.of(summary), new Document()));

    assertThat(repository.topLiked(500)).containsExactly(summary);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("whatsforlunch_ratings"),
        eq(RestaurantVoteSummary.class));
    assertThat(aggregation.getValue().toString())
        .contains("$group", "restaurantId", "upVotes", "downVotes", "voteCount", "vote")
        .contains("approvalRatio", "$sort", "voteCount", "$limit", "50");
  }

  @Test
  void summariesForRestaurantsMatchesCandidatesAndGroupsVotesInsideMongo() {
    var summary = new RestaurantVoteSummary("restaurant-1", 1, 1, 2);
    when(mongo.aggregate(any(Aggregation.class), eq("whatsforlunch_ratings"),
        eq(RestaurantVoteSummary.class)))
        .thenReturn(new AggregationResults<>(List.of(summary), new Document()));

    assertThat(repository.summariesForRestaurants(List.of("restaurant-1", "restaurant-2")))
        .containsExactly(summary);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(aggregation.capture(), eq("whatsforlunch_ratings"),
        eq(RestaurantVoteSummary.class));
    assertThat(aggregation.getValue().toString())
        .contains("$match", "$in", "restaurant-1", "restaurant-2")
        .contains("$group", "restaurantId", "upVotes", "downVotes", "voteCount", "vote")
        .doesNotContain("$limit");
  }

  @Test
  void summariesForRestaurantsSkipsMongoForEmptyCandidates() {
    assertThat(repository.summariesForRestaurants(List.of())).isEmpty();

    verifyNoInteractions(mongo);
  }
}

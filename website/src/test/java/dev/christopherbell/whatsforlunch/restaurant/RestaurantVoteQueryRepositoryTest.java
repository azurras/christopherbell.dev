package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteSummary;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestaurantVoteQueryRepositoryTest {
  private KindScopedMongoOperations<RestaurantVote> votes;
  private RestaurantVoteQueryRepository repository;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    var factory = mock(DomainMongoOperationsFactory.class);
    votes = (KindScopedMongoOperations<RestaurantVote>) mock(KindScopedMongoOperations.class);
    when(factory.forType(RestaurantVote.class)).thenReturn(votes);
    repository = new RestaurantVoteQueryRepository(factory);
  }

  @Test
  void topLikedUsesTheKindScopedAggregationAndBoundsTheResult() {
    var summary = new RestaurantVoteSummary("restaurant-1", 2, 1, 3);
    when(votes.aggregate(any(KindScopedAggregation.class),
        org.mockito.ArgumentMatchers.eq(RestaurantVoteSummary.class)))
        .thenReturn(List.of(summary));

    assertThat(repository.topLiked(500)).containsExactly(summary);
  }

  @Test
  void summariesForRestaurantsUsesKindScopedAggregationAndSkipsEmptyCandidates() {
    var summary = new RestaurantVoteSummary("restaurant-1", 1, 1, 2);
    when(votes.aggregate(any(KindScopedAggregation.class),
        org.mockito.ArgumentMatchers.eq(RestaurantVoteSummary.class)))
        .thenReturn(List.of(summary));

    assertThat(repository.summariesForRestaurants(List.of("restaurant-1", "restaurant-2")))
        .containsExactly(summary);

    org.mockito.Mockito.reset(votes);
    assertThat(repository.summariesForRestaurants(List.of())).isEmpty();
    verifyNoInteractions(votes);
  }
}

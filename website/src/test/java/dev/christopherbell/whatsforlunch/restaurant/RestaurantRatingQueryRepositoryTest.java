package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.whatsforlunch.restaurant.rating.RestaurantRatingQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.rating.RestaurantRatingSummary;
import java.util.List;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("Restaurant rating query repository")
class RestaurantRatingQueryRepositoryTest {
  @Mock private MongoTemplate mongo;
  private RestaurantRatingQueryRepository repository;

  @BeforeEach
  void setUp() {
    repository = new RestaurantRatingQueryRepository(mongo);
  }

  @Test
  void topRated_groupsSortsAndLimitsInsideMongo() {
    var summary = new RestaurantRatingSummary("restaurant-1", 2, 10);
    when(mongo.aggregate(
        any(Aggregation.class),
        eq("whatsforlunch_ratings"),
        eq(RestaurantRatingSummary.class)))
        .thenReturn(new AggregationResults<>(List.of(summary), new Document()));

    assertThat(repository.topRated(500)).containsExactly(summary);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(
        aggregation.capture(),
        eq("whatsforlunch_ratings"),
        eq(RestaurantRatingSummary.class));
    assertThat(aggregation.getValue().toString())
        .contains("$group", "restaurantId", "ratingCount", "ratingSum")
        .contains("$sort", "averageRating", "$limit", "50");
  }
}

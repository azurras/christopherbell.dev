package dev.christopherbell.whatsforlunch.restaurant.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RestaurantVoteSummaryTest {

  @ParameterizedTest
  @MethodSource("invalidSummaries")
  void rejectsMalformedAggregateInputs(String restaurantId, int upVotes, int downVotes, int voteCount) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RestaurantVoteSummary(restaurantId, upVotes, downVotes, voteCount))
        .withMessage("Restaurant vote summary is invalid");
  }

  @ParameterizedTest
  @MethodSource("validSummaries")
  void acceptsConsistentAggregateInputs(
      String restaurantId,
      int upVotes,
      int downVotes,
      int voteCount
  ) {
    var summary = new RestaurantVoteSummary(restaurantId, upVotes, downVotes, voteCount);

    assertThat(summary)
        .extracting(
            RestaurantVoteSummary::restaurantId,
            RestaurantVoteSummary::upVotes,
            RestaurantVoteSummary::downVotes,
            RestaurantVoteSummary::voteCount)
        .containsExactly(restaurantId, upVotes, downVotes, voteCount);
  }

  private static Stream<Arguments> invalidSummaries() {
    return Stream.of(
        Arguments.of(Named.of("blank id", " "), 0, 0, 0),
        Arguments.of(Named.of("negative up votes", "restaurant-1"), -1, 1, 0),
        Arguments.of(Named.of("negative down votes", "restaurant-1"), 1, -1, 0),
        Arguments.of(Named.of("inconsistent total", "restaurant-1"), 1, 1, 1));
  }

  private static Stream<Arguments> validSummaries() {
    return Stream.of(
        Arguments.of(Named.of("zero votes", "restaurant-0"), 0, 0, 0),
        Arguments.of(Named.of("mixed votes", "restaurant-1"), 3, 2, 5));
  }
}

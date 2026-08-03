package dev.christopherbell.whatsforlunch.restaurant.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.rating.RestaurantRatingSummary;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Rating-weighted restaurant selector")
class RatingWeightedRestaurantSelectorTest {
  private final RatingWeightedRestaurantSelector selector =
      new RatingWeightedRestaurantSelector();

  @Test
  void unratedRestaurantsUseTheNeutralWeight() {
    assertThat(RatingWeightedRestaurantSelector.weightFor(null)).isEqualTo(1.0);
  }

  @Test
  void oneFiveStarRatingBlendsWithThreeNeutralRatings() {
    var summary = new RestaurantRatingSummary("sparse", 1, 5);

    assertThat(RatingWeightedRestaurantSelector.weightFor(summary))
        .isCloseTo(1.25, within(0.000_000_001));
  }

  @Test
  void adjustedRatingWeightsInterpolateBetweenApprovedAnchors() {
    assertThat(RatingWeightedRestaurantSelector.interpolateWeight(1.0)).isEqualTo(0.35);
    assertThat(RatingWeightedRestaurantSelector.interpolateWeight(1.5))
        .isCloseTo(0.475, within(0.000_000_001));
    assertThat(RatingWeightedRestaurantSelector.interpolateWeight(2.5))
        .isCloseTo(0.80, within(0.000_000_001));
    assertThat(RatingWeightedRestaurantSelector.interpolateWeight(3.5))
        .isCloseTo(1.25, within(0.000_000_001));
    assertThat(RatingWeightedRestaurantSelector.interpolateWeight(4.5))
        .isCloseTo(1.75, within(0.000_000_001));
    assertThat(RatingWeightedRestaurantSelector.interpolateWeight(5.0)).isEqualTo(2.0);
  }

  @Test
  void deterministicDrawSelectsWithoutReplacementAndPreservesInput() {
    var first = restaurant("first");
    var second = restaurant("second");
    var third = restaurant("third");
    var candidates = List.of(first, second, third);
    var samples = new ArrayDeque<>(List.of(0.75, 0.25));

    var selected = selector.select(
        candidates, Map.of(), 2, () -> samples.removeFirst());

    assertThat(selected).containsExactly(third, first);
    assertThat(selected).doesNotHaveDuplicates();
    assertThat(candidates).containsExactly(first, second, third);
  }

  @Test
  void rejectsDuplicateCandidateIds() {
    assertThatThrownBy(() -> selector.select(
        List.of(restaurant("same"), restaurant("same")),
        Map.of(),
        1,
        () -> 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMalformedRatingSummary() {
    var candidate = restaurant("broken");
    var malformed = new RestaurantRatingSummary("broken", 2, 11);

    assertThatThrownBy(() -> selector.select(
        List.of(candidate),
        Map.of("broken", malformed),
        1,
        () -> 0.5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRandomSamplesOutsideTheUnitInterval() {
    assertThatThrownBy(() -> selector.select(
        List.of(restaurant("candidate")),
        Map.of(),
        1,
        () -> 1.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixedSeedDrawFavorsHighThenNeutralThenLowRatings() {
    var low = restaurant("low");
    var neutral = restaurant("neutral");
    var high = restaurant("high");
    var summaries = Map.of(
        "low", new RestaurantRatingSummary("low", 1_000, 1_000),
        "high", new RestaurantRatingSummary("high", 1_000, 5_000));
    var counts = new HashMap<String, Integer>();
    var random = new Random(20_260_802L);

    for (int draw = 0; draw < 20_000; draw++) {
      var selected = selector.select(
          List.of(low, neutral, high), summaries, 1, random::nextDouble);
      counts.merge(selected.getFirst().getId(), 1, Integer::sum);
    }

    int lowCount = counts.getOrDefault("low", 0);
    int neutralCount = counts.getOrDefault("neutral", 0);
    int highCount = counts.getOrDefault("high", 0);
    assertThat(highCount).isGreaterThan(neutralCount);
    assertThat(neutralCount).isGreaterThan(lowCount);
    assertThat((double) highCount / neutralCount).isBetween(1.8, 2.2);
    assertThat((double) lowCount / neutralCount).isBetween(0.30, 0.40);
  }

  private static Restaurant restaurant(String id) {
    return Restaurant.builder().id(id).name(id).build();
  }
}

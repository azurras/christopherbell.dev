package dev.christopherbell.whatsforlunch.restaurant.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteSummary;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ApprovalWeightedRestaurantSelectorTest {
  private final ApprovalWeightedRestaurantSelector selector = new ApprovalWeightedRestaurantSelector();

  @Test
  void usesNeutralWeightForNoVotesAndAdjustedApprovalForSparseVotes() {
    assertThat(ApprovalWeightedRestaurantSelector.weightFor(null)).isEqualTo(1.0);
    assertThat(ApprovalWeightedRestaurantSelector.weightFor(
        new RestaurantVoteSummary("up", 1, 0, 1))).isCloseTo(1.25, within(0.000_000_001));
    assertThat(ApprovalWeightedRestaurantSelector.weightFor(
        new RestaurantVoteSummary("down", 0, 1, 1))).isCloseTo(0.8375, within(0.000_000_001));
  }

  @Test
  void interpolatesBetweenApprovalAnchors() {
    assertThat(ApprovalWeightedRestaurantSelector.interpolateWeight(0.0)).isEqualTo(0.35);
    assertThat(ApprovalWeightedRestaurantSelector.interpolateWeight(0.5)).isEqualTo(1.0);
    assertThat(ApprovalWeightedRestaurantSelector.interpolateWeight(1.0)).isEqualTo(2.0);
  }

  @Test
  void selectsWithoutReplacementAndPreservesCandidates() {
    var first = restaurant("first");
    var second = restaurant("second");
    var third = restaurant("third");
    var candidates = List.of(first, second, third);
    var samples = new ArrayDeque<>(List.of(0.75, 0.25));

    var selected = selector.select(candidates, Map.of(), 2, () -> samples.removeFirst());

    assertThat(selected).containsExactly(third, first).doesNotHaveDuplicates();
    assertThat(candidates).containsExactly(first, second, third);
  }

  @Test
  void rejectsDuplicateCandidatesAndInvalidRandomSamples() {
    assertThatThrownBy(() -> selector.select(
        List.of(restaurant("same"), restaurant("same")), Map.of(), 1, () -> 0.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> selector.select(
        List.of(restaurant("candidate")), Map.of(), 1, () -> 1.0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deterministicDrawFavorsUpOverNeutralOverDown() {
    var low = restaurant("low");
    var neutral = restaurant("neutral");
    var high = restaurant("high");
    var summaries = Map.of(
        "low", new RestaurantVoteSummary("low", 0, 1_000, 1_000),
        "high", new RestaurantVoteSummary("high", 1_000, 0, 1_000));
    var counts = new HashMap<String, Integer>();
    var random = new Random(20_260_802L);

    for (int draw = 0; draw < 20_000; draw++) {
      var selected = selector.select(List.of(low, neutral, high), summaries, 1, random::nextDouble);
      counts.merge(selected.getFirst().getId(), 1, Integer::sum);
    }

    assertThat(counts.getOrDefault("high", 0)).isGreaterThan(counts.getOrDefault("neutral", 0));
    assertThat(counts.getOrDefault("neutral", 0)).isGreaterThan(counts.getOrDefault("low", 0));
  }

  private static Restaurant restaurant(String id) {
    return Restaurant.builder().id(id).name(id).build();
  }
}

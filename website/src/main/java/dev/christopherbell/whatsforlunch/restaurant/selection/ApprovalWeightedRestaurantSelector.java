package dev.christopherbell.whatsforlunch.restaurant.selection;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

/** Selects unique restaurant candidates using confidence-adjusted approval weights. */
@Component
public final class ApprovalWeightedRestaurantSelector {
  private static final double PRIOR_UP_VOTES = 1.5;
  private static final double PRIOR_VOTE_COUNT = 3.0;

  /** Selects at most {@code requestedCount} candidates without replacement. */
  public List<Restaurant> select(
      List<Restaurant> candidates,
      Map<String, RestaurantVoteSummary> summariesByRestaurantId,
      int requestedCount
  ) {
    return select(candidates, summariesByRestaurantId, requestedCount,
        ThreadLocalRandom.current()::nextDouble);
  }

  List<Restaurant> select(
      List<Restaurant> candidates,
      Map<String, RestaurantVoteSummary> summariesByRestaurantId,
      int requestedCount,
      DoubleSupplier random
  ) {
    Objects.requireNonNull(candidates, "candidates");
    Objects.requireNonNull(summariesByRestaurantId, "summariesByRestaurantId");
    Objects.requireNonNull(random, "random");
    if (requestedCount < 0) {
      throw new IllegalArgumentException("requestedCount must not be negative");
    }
    if (requestedCount == 0 || candidates.isEmpty()) {
      return List.of();
    }

    var seenIds = new HashSet<String>();
    var remaining = new ArrayList<WeightedRestaurant>(candidates.size());
    for (Restaurant candidate : candidates) {
      if (candidate == null || candidate.getId() == null || candidate.getId().isBlank()) {
        throw new IllegalArgumentException("candidate restaurant id must not be blank");
      }
      if (!seenIds.add(candidate.getId())) {
        throw new IllegalArgumentException("candidate restaurant ids must be unique: "
            + candidate.getId());
      }
      var summary = summariesByRestaurantId.get(candidate.getId());
      if (summary != null && !candidate.getId().equals(summary.restaurantId())) {
        throw new IllegalArgumentException("vote summary id does not match candidate: "
            + candidate.getId());
      }
      remaining.add(new WeightedRestaurant(candidate, weightFor(summary)));
    }

    var selected = new ArrayList<Restaurant>(Math.min(requestedCount, remaining.size()));
    while (selected.size() < requestedCount && !remaining.isEmpty()) {
      double totalWeight = remaining.stream().mapToDouble(WeightedRestaurant::weight).sum();
      double sample = random.getAsDouble();
      if (!Double.isFinite(sample) || sample < 0.0 || sample >= 1.0) {
        throw new IllegalArgumentException("random sample must be in [0, 1)");
      }
      double target = sample * totalWeight;
      double cumulativeWeight = 0.0;
      int selectedIndex = remaining.size() - 1;
      for (int index = 0; index < remaining.size(); index++) {
        cumulativeWeight += remaining.get(index).weight();
        if (target < cumulativeWeight) {
          selectedIndex = index;
          break;
        }
      }
      selected.add(remaining.remove(selectedIndex).restaurant());
    }
    return List.copyOf(selected);
  }

  static double weightFor(RestaurantVoteSummary summary) {
    if (summary == null || summary.voteCount() == 0) {
      return 1.0;
    }
    double adjustedApproval =
        (summary.upVotes() + PRIOR_UP_VOTES) / (summary.voteCount() + PRIOR_VOTE_COUNT);
    return interpolateWeight(adjustedApproval);
  }

  static double interpolateWeight(double approval) {
    if (!Double.isFinite(approval) || approval < 0.0 || approval > 1.0) {
      throw new IllegalArgumentException("adjusted approval must be in [0, 1]");
    }
    return approval <= 0.5
        ? 0.35 + (1.0 - 0.35) * (approval / 0.5)
        : 1.0 + (2.0 - 1.0) * ((approval - 0.5) / 0.5);
  }

  private record WeightedRestaurant(Restaurant restaurant, double weight) {}
}

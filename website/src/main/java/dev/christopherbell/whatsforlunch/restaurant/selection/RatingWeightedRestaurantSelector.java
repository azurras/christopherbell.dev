package dev.christopherbell.whatsforlunch.restaurant.selection;

import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.rating.RestaurantRatingSummary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Component;

/** Selects unique restaurant candidates using confidence-adjusted rating weights. */
@Component
public final class RatingWeightedRestaurantSelector {
  private static final double PRIOR_RATING = 3.0;
  private static final int PRIOR_RATING_COUNT = 3;
  private static final double[] RATING_WEIGHTS = {0.35, 0.60, 1.00, 1.50, 2.00};

  /** Selects at most {@code requestedCount} candidates without replacement. */
  public List<Restaurant> select(
      List<Restaurant> candidates,
      Map<String, RestaurantRatingSummary> summariesByRestaurantId,
      int requestedCount
  ) {
    return select(
        candidates,
        summariesByRestaurantId,
        requestedCount,
        ThreadLocalRandom.current()::nextDouble);
  }

  List<Restaurant> select(
      List<Restaurant> candidates,
      Map<String, RestaurantRatingSummary> summariesByRestaurantId,
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
        throw new IllegalArgumentException(
            "candidate restaurant ids must be unique: " + candidate.getId());
      }
      var summary = summariesByRestaurantId.get(candidate.getId());
      if (summary != null && !candidate.getId().equals(summary.restaurantId())) {
        throw new IllegalArgumentException(
            "rating summary id does not match candidate: " + candidate.getId());
      }
      remaining.add(new WeightedRestaurant(candidate, weightFor(summary)));
    }

    var selected = new ArrayList<Restaurant>(
        Math.min(requestedCount, remaining.size()));
    while (selected.size() < requestedCount && !remaining.isEmpty()) {
      double totalWeight = remaining.stream()
          .mapToDouble(WeightedRestaurant::weight)
          .sum();
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

  static double weightFor(RestaurantRatingSummary summary) {
    if (summary == null) {
      return interpolateWeight(PRIOR_RATING);
    }
    if (summary.ratingCount() <= 0
        || summary.ratingSum() < summary.ratingCount()
        || (long) summary.ratingSum() > (long) summary.ratingCount() * 5L) {
      throw new IllegalArgumentException("rating summary count and sum are invalid");
    }
    double adjustedRating =
        (summary.ratingSum() + PRIOR_RATING * PRIOR_RATING_COUNT)
            / (summary.ratingCount() + (double) PRIOR_RATING_COUNT);
    return interpolateWeight(adjustedRating);
  }

  static double interpolateWeight(double adjustedRating) {
    if (!Double.isFinite(adjustedRating)
        || adjustedRating < 1.0
        || adjustedRating > 5.0) {
      throw new IllegalArgumentException("adjusted rating must be between 1 and 5");
    }
    int lowerAnchor = (int) Math.floor(adjustedRating);
    if (lowerAnchor == 5) {
      return RATING_WEIGHTS[4];
    }
    double lowerWeight = RATING_WEIGHTS[lowerAnchor - 1];
    double upperWeight = RATING_WEIGHTS[lowerAnchor];
    return lowerWeight + (upperWeight - lowerWeight) * (adjustedRating - lowerAnchor);
  }

  private record WeightedRestaurant(Restaurant restaurant, double weight) {}
}

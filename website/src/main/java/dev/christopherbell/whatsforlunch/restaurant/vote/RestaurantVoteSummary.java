package dev.christopherbell.whatsforlunch.restaurant.vote;

/** Aggregate binary vote totals for one restaurant, calculated inside MongoDB. */
public record RestaurantVoteSummary(
    String restaurantId,
    int upVotes,
    int downVotes,
    int voteCount
) {
  public RestaurantVoteSummary {
    if (restaurantId == null || restaurantId.isBlank()
        || upVotes < 0 || downVotes < 0 || voteCount != upVotes + downVotes) {
      throw new IllegalArgumentException("Restaurant vote summary is invalid");
    }
  }
}

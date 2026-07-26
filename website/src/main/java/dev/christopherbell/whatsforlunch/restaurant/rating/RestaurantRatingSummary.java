package dev.christopherbell.whatsforlunch.restaurant.rating;

/** Aggregate rating totals for one restaurant, calculated inside MongoDB. */
public record RestaurantRatingSummary(
    String restaurantId,
    int ratingCount,
    int ratingSum
) {}

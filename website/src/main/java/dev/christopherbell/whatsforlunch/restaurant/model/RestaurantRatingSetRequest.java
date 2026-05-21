package dev.christopherbell.whatsforlunch.restaurant.model;

/** Request to rate a restaurant without putting provider ids in the URL path. */
public record RestaurantRatingSetRequest(String restaurantId, Object rating) {}

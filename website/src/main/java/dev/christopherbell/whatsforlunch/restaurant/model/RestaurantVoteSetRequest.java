package dev.christopherbell.whatsforlunch.restaurant.model;

/** Request to vote on a restaurant without putting provider ids in the URL path. */
public record RestaurantVoteSetRequest(String restaurantId, Object vote) {}

package dev.christopherbell.whatsforlunch.restaurant.model;

/** Request to favorite or unfavorite a restaurant without putting provider ids in the URL path. */
public record RestaurantFavoriteRequest(String restaurantId) {}

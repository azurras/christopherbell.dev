package dev.christopherbell.whatsforlunch.restaurant.model;

/** Request to cast or update the caller's vote in a WFL session. */
public record WhatsForLunchSessionVoteRequest(String restaurantId) {}

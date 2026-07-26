package dev.christopherbell.whatsforlunch.restaurant.model;

import java.time.Instant;

/** Operator-visible record participating in a proposed duplicate merge. */
public record RestaurantDedupeCandidate(
    String id,
    String name,
    Address address,
    Instant createdOn,
    Instant lastUpdatedOn
) {}

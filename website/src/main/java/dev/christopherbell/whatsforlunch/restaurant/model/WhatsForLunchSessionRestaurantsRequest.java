package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Request to replace the three restaurants shown in a shared WFL session. */
public record WhatsForLunchSessionRestaurantsRequest(List<String> restaurantIds) {}

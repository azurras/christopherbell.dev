package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/** Request to create a shared WFL voting session from the current three picks. */
public record WhatsForLunchSessionCreateRequest(
    List<String> restaurantIds,
    List<String> invitedUsernames
) {}

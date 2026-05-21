package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;

/**
 * Request body for saving What's For Lunch cuisine filters.
 *
 * @param cuisines normalized cuisine labels the user wants to include
 * @param radiusMiles the user's preferred nearby search radius
 */
public record WhatsForLunchPreferenceRequest(List<String> cuisines, Integer radiusMiles) {
}

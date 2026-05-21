package dev.christopherbell.whatsforlunch.restaurant.model;

import java.util.List;
import lombok.Builder;

/**
 * API response for a user's saved What's For Lunch filters.
 *
 * @param cuisines normalized cuisine labels the user wants to include
 * @param radiusMiles the user's preferred nearby search radius
 */
@Builder
public record WhatsForLunchPreferenceDetail(List<String> cuisines, Integer radiusMiles) {
}

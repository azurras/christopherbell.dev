package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantWebsite;

/** Normalizes restaurant links to absolute credential-free HTTP(S) URLs. */
public final class RestaurantWebsiteUrlPolicy {
  private RestaurantWebsiteUrlPolicy() {}

  /** Returns a normalized URL or rejects a nonblank unsafe value. */
  public static String requireSafe(String value) throws InvalidRequestException {
    return RestaurantWebsite.validateForWrite(value);
  }

  /** Returns a safe URL for imported/rendered data, or null when unsafe. */
  public static String safeOrNull(String value) {
    return RestaurantWebsite.safeForDisplay(value);
  }
}

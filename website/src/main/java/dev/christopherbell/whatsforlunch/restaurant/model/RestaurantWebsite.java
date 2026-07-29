package dev.christopherbell.whatsforlunch.restaurant.model;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.net.URI;
import java.util.Locale;

/** Validates restaurant websites before persistence and suppresses unsafe legacy values on read. */
public final class RestaurantWebsite {
  private RestaurantWebsite() {}

  /** Returns a normalized optional website or rejects a non-HTTP(S) input before it is persisted. */
  public static String validateForWrite(String website) throws InvalidRequestException {
    if (website == null || website.isBlank()) {
      return null;
    }
    var normalized = website.strip();
    if (!isAbsoluteHttpUrl(normalized)) {
      throw new InvalidRequestException("Restaurant website must be an absolute HTTP(S) URL.");
    }
    return normalized;
  }

  /** Returns an active-link-safe persisted website, or {@code null} for a legacy unsafe value. */
  public static String safeForDisplay(String website) {
    if (website == null || website.isBlank()) {
      return null;
    }
    var normalized = website.strip();
    return isAbsoluteHttpUrl(normalized) ? normalized : null;
  }

  /** Identifies absolute HTTP(S) URLs with a host suitable for an active browser link. */
  public static boolean isAbsoluteHttpUrl(String website) {
    try {
      var uri = URI.create(website);
      var scheme = uri.getScheme();
      return uri.isAbsolute()
          && uri.getHost() != null
          && scheme != null
          && ("http".equals(scheme.toLowerCase(Locale.ROOT))
              || "https".equals(scheme.toLowerCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}

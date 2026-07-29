package dev.christopherbell.whatsforlunch.restaurant.model;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Validates restaurant websites before persistence and suppresses unsafe legacy values on read. */
public final class RestaurantWebsite {
  private RestaurantWebsite() {}

  /** Returns a normalized optional website or rejects a non-HTTP(S) input before it is persisted. */
  public static String validateForWrite(String website) throws InvalidRequestException {
    if (website == null || website.isBlank()) {
      return null;
    }
    var normalized = normalize(website);
    if (normalized == null) {
      throw new InvalidRequestException("Restaurant website must be an absolute HTTP(S) URL.");
    }
    return normalized;
  }

  /** Returns an active-link-safe persisted website, or {@code null} for a legacy unsafe value. */
  public static String safeForDisplay(String website) {
    if (website == null || website.isBlank()) {
      return null;
    }
    return normalize(website);
  }

  /** Identifies absolute HTTP(S) URLs with a host suitable for an active browser link. */
  public static boolean isAbsoluteHttpUrl(String website) {
    return normalize(website) != null;
  }

  private static String normalize(String website) {
    try {
      var uri = new URI(website.strip());
      var scheme = uri.getScheme();
      if (!uri.isAbsolute()
          || uri.getHost() == null
          || uri.getHost().isBlank()
          || uri.getUserInfo() != null
          || scheme == null
          || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
        return null;
      }
      return new URI(
          scheme.toLowerCase(Locale.ROOT),
          null,
          uri.getHost(),
          uri.getPort(),
          uri.getPath(),
          uri.getQuery(),
          uri.getFragment()).toASCIIString();
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }
}

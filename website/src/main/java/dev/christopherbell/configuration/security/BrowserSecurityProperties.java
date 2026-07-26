package dev.christopherbell.configuration.security;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Browser-facing security settings that must be stable across request boundaries. */
@ConfigurationProperties("app.browser-security")
public record BrowserSecurityProperties(
    URI publicBaseUrl,
    boolean authenticationCookieSecure,
    boolean hstsEnabled
) {
  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  public BrowserSecurityProperties {
    if (publicBaseUrl == null
        || !ALLOWED_SCHEMES.contains(normalize(publicBaseUrl.getScheme()))
        || publicBaseUrl.getHost() == null
        || publicBaseUrl.getUserInfo() != null
        || publicBaseUrl.getQuery() != null
        || publicBaseUrl.getFragment() != null
        || !hasEmptyPath(publicBaseUrl)) {
      throw new IllegalArgumentException(
          "app.browser-security.public-base-url must be an http(s) origin without path, query, fragment, or user info");
    }
  }

  private static boolean hasEmptyPath(URI uri) {
    return uri.getPath() == null || uri.getPath().isEmpty();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
  }
}

package dev.christopherbell.configuration.security;

import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Creates the credential and non-secret UI-state cookies for browser authentication. */
@Component
public class BrowserAuthenticationCookies {
  public static final String AUTH_COOKIE_NAME = "CBELL_AUTH";
  public static final String AUTH_STATE_COOKIE_NAME = "CBELL_AUTH_STATE";
  private static final Duration AUTH_LIFETIME = Duration.ofDays(30);

  private final BrowserSecurityProperties properties;

  public BrowserAuthenticationCookies(BrowserSecurityProperties properties) {
    this.properties = properties;
  }

  /** Returns cookies for a successful browser login. */
  public List<ResponseCookie> authenticated(String opaqueSessionToken) {
    if (opaqueSessionToken == null || opaqueSessionToken.isBlank()) {
      throw new IllegalArgumentException("Authenticated browser cookie requires a session token.");
    }
    return List.of(
        cookie(AUTH_COOKIE_NAME, opaqueSessionToken, true, AUTH_LIFETIME),
        cookie(AUTH_STATE_COOKIE_NAME, "1", false, AUTH_LIFETIME));
  }

  /** Returns zero-age cookies that clear browser authentication state. */
  public List<ResponseCookie> cleared() {
    return List.of(
        cookie(AUTH_COOKIE_NAME, "", true, Duration.ZERO),
        cookie(AUTH_STATE_COOKIE_NAME, "", false, Duration.ZERO));
  }

  private ResponseCookie cookie(String name, String value, boolean httpOnly, Duration maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(httpOnly)
        .secure(properties.authenticationCookieSecure())
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}

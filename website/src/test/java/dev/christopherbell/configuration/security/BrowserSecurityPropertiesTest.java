package dev.christopherbell.configuration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrowserSecurityPropertiesTest {

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:8081", "https://www.christopherbell.dev"})
  void publicBaseUrl_acceptsOnlyCompleteHttpOrigins(String value) {
    var properties = new BrowserSecurityProperties(URI.create(value), false, false);

    assertEquals(value, properties.publicBaseUrl().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "ftp://example.test",
      "https://user@example.test",
      "https://example.test/path",
      "https://example.test?query=value",
      "https://example.test#fragment",
      "relative/path"
  })
  void publicBaseUrl_rejectsValuesThatAreNotOrigins(String value) {
    assertThrows(IllegalArgumentException.class,
        () -> new BrowserSecurityProperties(URI.create(value), false, false));
  }

  @Test
  void authenticationCookies_applyBrowserAndEnvironmentBoundaries() {
    var localCookies = new BrowserAuthenticationCookies(
        new BrowserSecurityProperties(URI.create("http://localhost:8081"), false, false))
        .authenticated("header.payload.signature");
    var productionCookies = new BrowserAuthenticationCookies(
        new BrowserSecurityProperties(URI.create("https://www.christopherbell.dev"), true, true))
        .authenticated("header.payload.signature");

    var localAuth = localCookies.getFirst();
    var localMarker = localCookies.get(1);
    assertEquals(BrowserAuthenticationCookies.AUTH_COOKIE_NAME, localAuth.getName());
    assertTrue(localAuth.isHttpOnly());
    assertFalse(localAuth.isSecure());
    assertEquals("Lax", localAuth.getSameSite());
    assertEquals("/", localAuth.getPath());
    assertEquals(Duration.ofDays(30), localAuth.getMaxAge());
    assertFalse(localMarker.isHttpOnly());
    assertTrue(productionCookies.getFirst().isSecure());
    assertTrue(productionCookies.get(1).isSecure());
  }

  @Test
  void clearedCookies_expireCredentialAndMarkerImmediately() {
    var cookies = new BrowserAuthenticationCookies(
        new BrowserSecurityProperties(URI.create("https://example.test"), true, true))
        .cleared();

    assertEquals(Duration.ZERO, cookies.getFirst().getMaxAge());
    assertEquals(Duration.ZERO, cookies.get(1).getMaxAge());
    assertTrue(cookies.getFirst().isHttpOnly());
    assertFalse(cookies.get(1).isHttpOnly());
  }
}

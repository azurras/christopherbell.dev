package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.configuration.security.SecurityConfig;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Unit tests for public route rules that should not require authentication.
 */
class SecurityConfigTest {

  @Test
  @DisplayName("Favicon is a public browser asset")
  void publicMatchers_whenFaviconRequested_matchesWithoutAuthentication() throws Exception {
    var request = new MockHttpServletRequest("GET", "/favicon.ico");
    request.setServletPath("/favicon.ico");

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("WFL ZIP nearby endpoint is public")
  void publicMatchers_whenWflZipNearbyRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/api/whatsforlunch/restaurant/2026-05-17/nearby/zip/78701";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Location ZIP coordinate endpoint is public")
  void publicMatchers_whenLocationZipRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/api/location/zip/78701";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("ZIP coordinate tool page is public")
  void publicMatchers_whenZipCoordinateToolRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/zip-coordinates";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Cane's Box Tracker tool page is public")
  void publicMatchers_whenCanesBoxTrackerToolRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/canes-box-tracker";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Cane's Box Tracker tool page is public with a trailing slash")
  void publicMatchers_whenCanesBoxTrackerToolTrailingSlashRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/canes-box-tracker/";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @Test
  @DisplayName("Notifications page shell is public")
  void publicMatchers_whenNotificationsPageRequested_matchesWithoutAuthentication() throws Exception {
    var path = "/notifications";
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);

    assertTrue(publicMatchers().stream().anyMatch(matcher -> matcher.matches(request)));
  }

  @SuppressWarnings("unchecked")
  private List<RequestMatcher> publicMatchers() throws Exception {
    Method method = SecurityConfig.class.getDeclaredMethod("publicMatchersList");
    method.setAccessible(true);
    return (List<RequestMatcher>) method.invoke(new SecurityConfig());
  }
}

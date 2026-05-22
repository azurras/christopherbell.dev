package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @SuppressWarnings("unchecked")
  private List<RequestMatcher> publicMatchers() throws Exception {
    Method method = SecurityConfig.class.getDeclaredMethod("publicMatchersList");
    method.setAccessible(true);
    return (List<RequestMatcher>) method.invoke(new SecurityConfig());
  }
}

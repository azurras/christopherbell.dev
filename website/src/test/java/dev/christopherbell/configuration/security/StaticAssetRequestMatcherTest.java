package dev.christopherbell.configuration.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class StaticAssetRequestMatcherTest {
  private final StaticAssetRequestMatcher matcher = new StaticAssetRequestMatcher();

  @Test
  @DisplayName("Only GET requests for the pinned Bootstrap WebJar are static assets")
  void bootstrapAssetsArePinnedAndGetOnly() {
    var currentAssets = List.of(
        "/webjars/bootstrap/5.3.8/css/bootstrap.min.css",
        "/webjars/bootstrap/5.3.8/js/bootstrap.bundle.min.js");

    for (var path : currentAssets) {
      assertTrue(matcher.matches(request("GET", path)));
      assertFalse(matcher.matches(request("POST", path)));
    }

    assertFalse(matcher.matches(
        request("GET", "/webjars/bootstrap/5.3.3/css/bootstrap.min.css")));
    assertFalse(matcher.matches(request("GET", "/webjars/other/1.0.0/example.js")));
  }

  private static MockHttpServletRequest request(String method, String path) {
    return new MockHttpServletRequest(method, path);
  }
}

package dev.christopherbell.configuration.security.browser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class InteractiveBrowserRequestTest {
  private final InteractiveBrowserRequest classifier = new InteractiveBrowserRequest();

  @Test
  void pageNavigationAndUserMutationsRenewTheSession() {
    var page = request("GET", "/music");
    page.addHeader("Accept", "text/html,application/xhtml+xml");

    assertTrue(classifier.matches(page));
    assertTrue(classifier.matches(request("POST", "/api/posts/2026-07-26")));
  }

  @Test
  void mediaRadioPollingAndStaticTrafficNeverRenewTheSession() {
    assertFalse(classifier.matches(request("GET", "/api/music/2026-07-28/radio")));
    assertFalse(classifier.matches(request("GET", "/api/music/2026-07-28/tracks/1/stream")));
    assertFalse(classifier.matches(request("GET", "/api/shared-folder/2026-07-17/media/jobs/1")));
    assertFalse(classifier.matches(request("POST", "/api/shared-folder/2026-07-17/radio/duration")));
    assertFalse(classifier.matches(request("GET", "/js/app.js")));
  }

  private MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }
}

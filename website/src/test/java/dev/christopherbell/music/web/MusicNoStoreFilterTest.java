package dev.christopherbell.music.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MusicNoStoreFilterTest {

  @Test
  void appliesSecurityHeadersEvenWhenMusicApiReturnsAnError() throws Exception {
    var response = new MockHttpServletResponse();
    FilterChain chain = (request, result) -> ((jakarta.servlet.http.HttpServletResponse) result)
        .setStatus(403);

    new MusicNoStoreFilter().doFilter(
        new MockHttpServletRequest("GET", "/api/music/2026-07-28/catalog"), response, chain);

    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void ignoresUnrelatedApis() throws Exception {
    var response = new MockHttpServletResponse();

    new MusicNoStoreFilter().doFilter(
        new MockHttpServletRequest("GET", "/api/posts/2026-07-28/feed"),
        response,
        new MockFilterChain());

    assertThat(response.getHeader("Cache-Control")).isNull();
  }
}

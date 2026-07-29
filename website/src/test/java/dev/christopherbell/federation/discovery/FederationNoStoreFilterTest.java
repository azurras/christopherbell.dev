package dev.christopherbell.federation.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FederationNoStoreFilterTest {

  @Test
  void appliesPublicNoStoreAndNosniffEvenWhenDiscoveryReturnsAnError() throws Exception {
    var response = new MockHttpServletResponse();
    FilterChain chain = (request, result) -> ((jakarta.servlet.http.HttpServletResponse) result)
        .setStatus(404);

    new FederationNoStoreFilter().doFilter(
        new MockHttpServletRequest("GET", "/.well-known/webfinger"), response, chain);

    assertThat(response.getHeader("Cache-Control")).isEqualTo("public, no-store");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("*");
  }

  @Test
  void coversActorCollectionsButIgnoresUnrelatedPages() throws Exception {
    var federationResponse = new MockHttpServletResponse();
    var pageResponse = new MockHttpServletResponse();
    var filter = new FederationNoStoreFilter();

    filter.doFilter(
        new MockHttpServletRequest("GET", "/ap/users/chris/outbox"),
        federationResponse,
        new MockFilterChain());
    filter.doFilter(
        new MockHttpServletRequest("GET", "/void"),
        pageResponse,
        new MockFilterChain());

    assertThat(federationResponse.getHeader("Cache-Control")).isEqualTo("public, no-store");
    assertThat(pageResponse.getHeader("Cache-Control")).isNull();
  }
}

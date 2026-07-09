package dev.christopherbell.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.christopherbell.configuration.filter.RateLimitFilter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class RateLimitFilterTest {

  @Test
  public void testRateLimitExceeded() throws ServletException, IOException {
    Supplier<Bucket> supplier = () -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(1, Duration.ofMinutes(1)))
        .build();
    RateLimitFilter filter = new RateLimitFilter(supplier, new ClientIpResolver(new ClientIpProperties()));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("1.1.1.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    // First request allowed
    filter.doFilter(request, response, chain);
    verify(chain, times(1)).doFilter(request, response);

    // Second request denied
    MockHttpServletResponse response2 = new MockHttpServletResponse();
    filter.doFilter(request, response2, chain);
    assertEquals(429, response2.getStatus());
  }

  @Test
  public void testDifferentIpSeparateBuckets() throws ServletException, IOException {
    Supplier<Bucket> supplier = () -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(1, Duration.ofMinutes(1)))
        .build();
    RateLimitFilter filter = new RateLimitFilter(supplier, new ClientIpResolver(new ClientIpProperties()));
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletRequest request1 = new MockHttpServletRequest();
    request1.setRemoteAddr("1.1.1.1");
    filter.doFilter(request1, new MockHttpServletResponse(), chain);

    MockHttpServletRequest request2 = new MockHttpServletRequest();
    request2.setRemoteAddr("2.2.2.2");
    filter.doFilter(request2, new MockHttpServletResponse(), chain);

    verify(chain, times(2))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  @Test
  public void spoofedForwardedForFromUntrustedRemoteUsesSameBucket()
      throws ServletException, IOException {
    Supplier<Bucket> supplier = () -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(1, Duration.ofMinutes(1)))
        .build();
    RateLimitFilter filter = new RateLimitFilter(supplier, new ClientIpResolver(new ClientIpProperties()));
    FilterChain chain = mock(FilterChain.class);

    MockHttpServletRequest request1 = new MockHttpServletRequest();
    request1.setRemoteAddr("10.0.0.20");
    request1.addHeader("X-Forwarded-For", "203.0.113.10");
    filter.doFilter(request1, new MockHttpServletResponse(), chain);

    MockHttpServletRequest request2 = new MockHttpServletRequest();
    request2.setRemoteAddr("10.0.0.20");
    request2.addHeader("X-Forwarded-For", "203.0.113.11");
    MockHttpServletResponse response2 = new MockHttpServletResponse();
    filter.doFilter(request2, response2, chain);

    assertEquals(429, response2.getStatus());
    verify(chain, times(1))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  @Test
  public void configuredEndpointGroupsUseSeparateBuckets()
      throws ServletException, IOException {
    var properties = new RateLimitProperties();
    properties.setRules(List.of(
        rule("auth", 1, List.of("POST"), List.of("/api/accounts/*/login")),
        rule("default", 2, List.of(), List.of("/**"))));
    RateLimitFilter filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()),
        properties);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request("POST", "/api/accounts/2024-12-15/login"), new MockHttpServletResponse(), chain);
    var deniedAuthResponse = new MockHttpServletResponse();
    filter.doFilter(request("POST", "/api/accounts/2024-12-15/login"), deniedAuthResponse, chain);
    var defaultResponse = new MockHttpServletResponse();
    filter.doFilter(request("GET", "/css/app.css"), defaultResponse, chain);

    assertEquals(429, deniedAuthResponse.getStatus());
    assertEquals(200, defaultResponse.getStatus());
    verify(chain, times(2))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  @Test
  public void stricterRuleOnlyAppliesToMatchingMethodAndPath()
      throws ServletException, IOException {
    var properties = new RateLimitProperties();
    properties.setRules(List.of(
        rule("vin-decode", 1, List.of("POST"), List.of("/api/vehicles/*/vin/decode")),
        rule("default", 10, List.of(), List.of("/**"))));
    RateLimitFilter filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()),
        properties);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request("GET", "/api/vehicles/2026-05-09/vin/decode"), new MockHttpServletResponse(), chain);
    filter.doFilter(request("POST", "/api/vehicles/2026-05-09/vin/decode"), new MockHttpServletResponse(), chain);
    var deniedVinResponse = new MockHttpServletResponse();
    filter.doFilter(request("POST", "/api/vehicles/2026-05-09/vin/decode"), deniedVinResponse, chain);

    assertEquals(429, deniedVinResponse.getStatus());
    verify(chain, times(2))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  private RateLimitProperties.Rule rule(
      String name,
      long capacity,
      List<String> methods,
      List<String> paths
  ) {
    return new RateLimitProperties.Rule(name, capacity, Duration.ofMinutes(1), methods, paths);
  }

  private MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr("1.1.1.1");
    return request;
  }
}

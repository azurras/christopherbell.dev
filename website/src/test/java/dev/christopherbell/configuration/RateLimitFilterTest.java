package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.christopherbell.configuration.filter.ApiErrorResponseWriter;
import dev.christopherbell.configuration.filter.RateLimitFilter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

public class RateLimitFilterTest {

  @Test
  void exhaustedBucketReturnsStandardEnvelopeAndRateLimitGuidance() throws Exception {
    var properties = new RateLimitProperties();
    properties.setRules(List.of(rule(
        "test", 1, Duration.ofSeconds(30), List.of("POST"), List.of("/api/test"))));
    var filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()),
        properties,
        new ApiErrorResponseWriter(new ObjectMapper()),
        Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC));
    var request = request("POST", "/api/test");
    var chain = mock(FilterChain.class);
    var allowed = new MockHttpServletResponse();
    filter.doFilter(request, allowed, chain);
    var denied = new MockHttpServletResponse();

    filter.doFilter(request, denied, chain);

    assertThat(allowed.getHeader("X-RateLimit-Limit")).isEqualTo("1");
    assertThat(allowed.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    assertThat(allowed.getHeader("X-RateLimit-Reset")).isEqualTo("1784980830");
    assertThat(denied.getStatus()).isEqualTo(429);
    assertThat(denied.getHeader("Retry-After")).isEqualTo("30");
    assertThat(denied.getHeader("X-RateLimit-Limit")).isEqualTo("1");
    assertThat(denied.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    assertThat(denied.getHeader("X-RateLimit-Reset")).isEqualTo("1784980830");
    assertThat(denied.getContentAsString())
        .contains("\"success\":false", "RATE_LIMITED");
  }

  @Test
  void fractionalRetryDelayRoundsUpToTheNextSecond() throws Exception {
    var properties = new RateLimitProperties();
    properties.setRules(List.of(rule(
        "test", 1, Duration.ofMillis(1500), List.of("POST"), List.of("/api/test"))));
    var filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()),
        properties,
        new ApiErrorResponseWriter(new ObjectMapper()),
        Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC));
    var request = request("POST", "/api/test");
    filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    var denied = new MockHttpServletResponse();

    filter.doFilter(request, denied, mock(FilterChain.class));

    assertThat(denied.getHeader("Retry-After")).isEqualTo("2");
    assertThat(denied.getHeader("X-RateLimit-Reset")).isEqualTo("1784980802");
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

  @Test
  public void sharedFolderUploadMutationAndTranscodeRulesPrecedeTheGenericApiMutationRule() {
    var rules = new RateLimitProperties().getRules();
    int apiMutations = indexOf(rules, "api-mutations");

    assertTrue(indexOf(rules, "shared-upload") < apiMutations);
    assertTrue(indexOf(rules, "shared-mutation") < apiMutations);
    assertTrue(indexOf(rules, "shared-transcode") < apiMutations);
    var upload = rules.stream().filter(rule -> "shared-upload".equals(rule.getName()))
        .findFirst().orElseThrow();
    assertEquals(240, upload.getCapacity());
    assertEquals(List.of("POST", "PUT", "PATCH"), upload.getMethods());
    assertEquals(List.of("/api/shared-folder/2026-07-17/uploads/**"), upload.getPaths());
    var mutation = rules.stream().filter(rule -> "shared-mutation".equals(rule.getName()))
        .findFirst().orElseThrow();
    assertEquals(60, mutation.getCapacity());
    assertEquals(List.of("POST", "PUT", "PATCH", "DELETE"), mutation.getMethods());
    assertEquals(List.of(
        "/api/shared-folder/2026-07-17/mutations/**",
        "/api/shared-folder/2026-07-17/recycle/**",
        "/api/shared-folder/2026-07-17/folders",
        "/api/shared-folder/2026-07-17/entries",
        "/api/shared-folder/2026-07-17/entries/**",
        "/api/shared-folder/2026-07-17/admin/recycle/**"), mutation.getPaths());
    var transcode = rules.stream().filter(rule -> "shared-transcode".equals(rule.getName()))
        .findFirst().orElseThrow();
    assertEquals(10, transcode.getCapacity());
    assertEquals(List.of("POST"), transcode.getMethods());
    assertEquals(List.of(
        "/api/shared-folder/2026-07-17/media/jobs",
        "/api/shared-folder/2026-07-17/media/fallback"), transcode.getPaths());
  }

  @Test
  void musicMutationsUseDedicatedLimitsWhilePlaybackReadsRemainOutsideThem() throws Exception {
    var rules = new RateLimitProperties().getRules();
    int apiMutations = indexOf(rules, "api-mutations");

    assertTrue(indexOf(rules, "music-library-mutation") < apiMutations);
    assertTrue(indexOf(rules, "music-metadata-mutation") < apiMutations);
    assertUsesDedicatedBucket(
        "POST", "/api/music/2026-07-28/library/playlists", "music-library-mutation");
    assertUsesDedicatedBucket(
        "PATCH", "/api/music/2026-07-28/tracks/id/metadata", "music-metadata-mutation");
    assertReadDoesNotUseDedicatedBucket(
        "GET", "/api/music/2026-07-28/tracks/id/stream", "music-metadata-mutation");
  }

  @Test
  public void sharedFolderUploadRequestsConsumeTheFirstMatchingDedicatedBucket()
      throws ServletException, IOException {
    RateLimitFilter filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()), new RateLimitProperties());
    FilterChain chain = mock(FilterChain.class);
    MockHttpServletResponse response = null;
    for (int count = 0; count < 241; count++) {
      response = new MockHttpServletResponse();
      filter.doFilter(request(
          "PUT", "/api/shared-folder/2026-07-17/uploads/session/chunks/0"), response, chain);
    }
    MockHttpServletResponse genericResponse = new MockHttpServletResponse();
    filter.doFilter(request("POST", "/api/ordinary/2026-07-17/action"), genericResponse, chain);

    assertEquals(429, response.getStatus());
    assertEquals(200, genericResponse.getStatus());
  }

  @Test
  void trustedProxyClientsReceiveIndependentBuckets() throws Exception {
    var clientIp = new ClientIpProperties();
    clientIp.setTrustedProxies(List.of("10.0.0.10"));
    Supplier<Bucket> supplier = () -> Bucket4j.builder()
        .addLimit(Bandwidth.simple(1, Duration.ofMinutes(1)))
        .build();
    RateLimitFilter filter = new RateLimitFilter(supplier, new ClientIpResolver(clientIp));
    FilterChain chain = mock(FilterChain.class);

    var first = request("POST", "/api/shared-folder/2026-07-17/media/fallback");
    first.setRemoteAddr("10.0.0.10");
    first.addHeader("X-Forwarded-For", "203.0.113.10");
    filter.doFilter(first, new MockHttpServletResponse(), chain);
    var second = request("POST", "/api/shared-folder/2026-07-17/media/fallback");
    second.setRemoteAddr("10.0.0.10");
    second.addHeader("X-Forwarded-For", "203.0.113.11");
    var response = new MockHttpServletResponse();
    filter.doFilter(second, response, chain);

    assertEquals(200, response.getStatus());
    verify(chain, times(2))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  @Test
  void progressiveAndRangeReadsDoNotConsumeTheTranscodeMutationBucket() throws Exception {
    var properties = new RateLimitProperties();
    properties.setRules(List.of(
        rule("shared-transcode", 1, List.of("POST"),
            List.of("/api/shared-folder/2026-07-17/media/jobs")),
        rule("default", 10, List.of(), List.of("/**"))));
    RateLimitFilter filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()), properties);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request("POST", "/api/shared-folder/2026-07-17/media/jobs"),
        new MockHttpServletResponse(), chain);
    var range = request("GET", "/api/shared-folder/2026-07-17/media/jobs/job/stream");
    range.addHeader("Range", "bytes=0-1023");
    filter.doFilter(range, new MockHttpServletResponse(), chain);
    filter.doFilter(request("GET", "/api/shared-folder/2026-07-17/media/jobs/job/stream"),
        new MockHttpServletResponse(), chain);
    var deniedMutation = new MockHttpServletResponse();
    filter.doFilter(request("POST", "/api/shared-folder/2026-07-17/media/jobs"),
        deniedMutation, chain);

    assertEquals(429, deniedMutation.getStatus());
    verify(chain, times(3))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  @Test
  void everyDeployedSharedFolderMutationRouteUsesItsDedicatedFirstMatchRule() throws Exception {
    assertUsesDedicatedBucket("POST", "/api/shared-folder/2026-07-17/folders",
        "shared-mutation");
    assertUsesDedicatedBucket("PATCH", "/api/shared-folder/2026-07-17/entries/rename",
        "shared-mutation");
    assertUsesDedicatedBucket("POST", "/api/shared-folder/2026-07-17/entries/move",
        "shared-mutation");
    assertUsesDedicatedBucket("DELETE", "/api/shared-folder/2026-07-17/entries",
        "shared-mutation");
    assertUsesDedicatedBucket(
        "POST", "/api/shared-folder/2026-07-17/admin/recycle/item/restore",
        "shared-mutation");
    assertUsesDedicatedBucket(
        "DELETE", "/api/shared-folder/2026-07-17/admin/recycle/item",
        "shared-mutation");
    assertUsesDedicatedBucket("POST", "/api/shared-folder/2026-07-17/media/fallback",
        "shared-transcode");
  }

  @Test
  void deployedUploadMethodsUseTheUploadRuleAndReadMethodsRemainExcluded() throws Exception {
    assertUsesDedicatedBucket("POST", "/api/shared-folder/2026-07-17/uploads",
        "shared-upload");
    assertUsesDedicatedBucket(
        "PUT", "/api/shared-folder/2026-07-17/uploads/item/chunks/0", "shared-upload");
    assertUsesDedicatedBucket(
        "POST", "/api/shared-folder/2026-07-17/uploads/item/complete", "shared-upload");

    assertReadDoesNotUseDedicatedBucket(
        "GET", "/api/shared-folder/2026-07-17/uploads/item", "shared-upload");
    assertReadDoesNotUseDedicatedBucket(
        "HEAD", "/api/shared-folder/2026-07-17/content", "shared-mutation");
    assertReadDoesNotUseDedicatedBucket(
        "GET", "/api/shared-folder/2026-07-17/media/jobs/item/stream", "shared-transcode");
  }

  private int indexOf(List<RateLimitProperties.Rule> rules, String name) {
    for (int index = 0; index < rules.size(); index++) {
      if (name.equals(rules.get(index).getName())) {
        return index;
      }
    }
    return Integer.MAX_VALUE;
  }

  private RateLimitProperties.Rule rule(
      String name,
      long capacity,
      List<String> methods,
      List<String> paths
  ) {
    return new RateLimitProperties.Rule(name, capacity, Duration.ofMinutes(1), methods, paths);
  }

  private RateLimitProperties.Rule rule(
      String name,
      long capacity,
      Duration window,
      List<String> methods,
      List<String> paths
  ) {
    return new RateLimitProperties.Rule(name, capacity, window, methods, paths);
  }

  private void assertUsesDedicatedBucket(String method, String path, String ruleName)
      throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getRules().forEach(rule -> {
      if (ruleName.equals(rule.getName())) rule.setCapacity(1);
    });
    RateLimitFilter filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()), properties);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request(method, path), new MockHttpServletResponse(), chain);
    MockHttpServletResponse denied = new MockHttpServletResponse();
    filter.doFilter(request(method, path), denied, chain);

    assertEquals(429, denied.getStatus());
    assertThat(denied.getContentType()).startsWith("application/json");
    assertThat(denied.getContentAsString())
        .contains("\"success\":false", "RATE_LIMITED");
    verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  private void assertReadDoesNotUseDedicatedBucket(String method, String path, String ruleName)
      throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.getRules().forEach(rule -> {
      if (ruleName.equals(rule.getName())) rule.setCapacity(1);
    });
    RateLimitFilter filter = new RateLimitFilter(
        new ClientIpResolver(new ClientIpProperties()), properties);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request(method, path), new MockHttpServletResponse(), chain);
    MockHttpServletResponse second = new MockHttpServletResponse();
    filter.doFilter(request(method, path), second, chain);

    assertEquals(200, second.getStatus());
    verify(chain, times(2))
        .doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  private MockHttpServletRequest request(String method, String path) {
    var request = new MockHttpServletRequest(method, path);
    request.setRemoteAddr("1.1.1.1");
    return request;
  }
}

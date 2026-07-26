package dev.christopherbell.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import dev.christopherbell.post.preview.BoundedLinkPreviewHttpClient;
import dev.christopherbell.post.preview.LinkPreviewFetchException;
import dev.christopherbell.post.preview.PostLinkPreviewDestinationPolicy;
import dev.christopherbell.post.preview.PostLinkPreviewProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class BoundedLinkPreviewHttpClientTest {
  @Test
  void revalidatesEveryManualRedirectAndReturnsBoundedHtml() throws Exception {
    var http = Mockito.mock(HttpClient.class);
    var policy = Mockito.mock(PostLinkPreviewDestinationPolicy.class);
    var redirect = response(302, Map.of("location", List.of("https://next.example/final")), new byte[0]);
    var html = "<html><title>Safe</title></html>".getBytes(StandardCharsets.UTF_8);
    var success = response(200, Map.of("content-type", List.of("text/html; charset=utf-8")), html);
    when(http.send(
        any(HttpRequest.class),
        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
        .thenReturn(redirect, success);
    var client = new BoundedLinkPreviewHttpClient(http, policy, properties(1024));

    var fetched = client.fetch(URI.create("https://start.example/page"));

    assertThat(fetched.finalUri()).isEqualTo(URI.create("https://next.example/final"));
    assertThat(fetched.body()).isEqualTo(html);
    var destinations = ArgumentCaptor.forClass(URI.class);
    verify(policy, Mockito.times(2)).requirePublic(destinations.capture());
    assertThat(destinations.getAllValues()).containsExactly(
        URI.create("https://start.example/page"), URI.create("https://next.example/final"));
  }

  @Test
  void rejectsAStreamedBodyAboveTheConfiguredByteLimit() throws Exception {
    var http = Mockito.mock(HttpClient.class);
    var policy = Mockito.mock(PostLinkPreviewDestinationPolicy.class);
    var oversized = response(
        200,
        Map.of("content-type", List.of("text/html")),
        "12345".getBytes(StandardCharsets.UTF_8));
    when(http.send(
        any(HttpRequest.class),
        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
        .thenReturn(oversized);
    var client = new BoundedLinkPreviewHttpClient(http, policy, properties(4));

    assertThatThrownBy(() -> client.fetch(URI.create("https://public.example/")))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("RESPONSE_TOO_LARGE");
  }

  @Test
  void overallTimeoutCancelsTheBodyReadWithoutWaitingForTheSlowStream() throws Exception {
    var http = Mockito.mock(HttpClient.class);
    var policy = Mockito.mock(PostLinkPreviewDestinationPolicy.class);
    var slow = response(
        200,
        Map.of("content-type", List.of("text/html")),
        new InputStream() {
          @Override
          public int read() throws java.io.IOException {
            try {
              Thread.sleep(300);
              return -1;
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              return -1;
            }
          }
        });
    when(http.send(
        any(HttpRequest.class),
        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<java.io.InputStream>>any()))
        .thenReturn(slow);
    var properties = properties(1024);
    properties.setOverallTimeout(Duration.ofMillis(25));
    properties.setRequestTimeout(Duration.ofMillis(25));
    var client = new BoundedLinkPreviewHttpClient(http, policy, properties);
    var started = System.nanoTime();

    assertThatThrownBy(() -> client.fetch(URI.create("https://public.example/")))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("TIMEOUT");

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
  }

  @Test
  void overallTimeoutAlsoBoundsDestinationResolution() {
    var http = Mockito.mock(HttpClient.class);
    var policy = Mockito.mock(PostLinkPreviewDestinationPolicy.class);
    doAnswer(invocation -> {
      try {
        Thread.sleep(300);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
      }
      return null;
    }).when(policy).requirePublic(any(URI.class));
    var properties = properties(1024);
    properties.setOverallTimeout(Duration.ofMillis(25));
    properties.setRequestTimeout(Duration.ofMillis(25));
    var client = new BoundedLinkPreviewHttpClient(http, policy, properties);
    var started = System.nanoTime();

    assertThatThrownBy(() -> client.fetch(URI.create("https://slow-dns.example/")))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("TIMEOUT");

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
  }

  private PostLinkPreviewProperties properties(int maxBytes) {
    var properties = new PostLinkPreviewProperties();
    properties.setConnectTimeout(Duration.ofSeconds(2));
    properties.setRequestTimeout(Duration.ofSeconds(3));
    properties.setOverallTimeout(Duration.ofSeconds(5));
    properties.setMaxRedirects(3);
    properties.setMaxResponseBytes(maxBytes);
    properties.setAllowedContentTypes(List.of("text/html", "application/xhtml+xml"));
    return properties;
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<java.io.InputStream> response(
      int status, Map<String, List<String>> headers, byte[] body) {
    return response(status, headers, new ByteArrayInputStream(body));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<java.io.InputStream> response(
      int status, Map<String, List<String>> headers, InputStream body) {
    var response = Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
    when(response.body()).thenReturn(body);
    return response;
  }
}

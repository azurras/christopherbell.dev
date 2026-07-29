package dev.christopherbell.post.preview;

import dev.christopherbell.post.preview.PostLinkPreviewDestinationPolicy.ApprovedDestination;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fetches HTML with DNS-bound manual redirects and bounded response bodies. */
@Component
public class BoundedLinkPreviewHttpClient {
  private static final Map<String, String> REQUEST_HEADERS = Map.of(
      "Accept", "text/html, application/xhtml+xml",
      "User-Agent", "christopherbell.dev link preview fetcher");

  private final LinkPreviewHttpTransport transport;
  private final DestinationResolver destinationResolver;
  private final PostLinkPreviewProperties properties;

  @Autowired
  public BoundedLinkPreviewHttpClient(
      PostLinkPreviewProperties properties,
      PostLinkPreviewDestinationPolicy destinationPolicy
  ) {
    this(new LinkPreviewHttpTransport(properties.getConnectTimeout()),
        destinationPolicy::resolveApproved, properties);
  }

  BoundedLinkPreviewHttpClient(
      LinkPreviewHttpTransport transport,
      DestinationResolver destinationResolver,
      PostLinkPreviewProperties properties
  ) {
    this.transport = transport;
    this.destinationResolver = destinationResolver;
    this.properties = properties;
  }

  public FetchedPage fetch(URI initialUri) {
    var deadlineNanos = System.nanoTime() + properties.getOverallTimeout().toNanos();
    var current = initialUri;
    for (var redirects = 0; ; redirects++) {
      var destination = resolveApproved(current, remaining(deadlineNanos));
      var response = transport.get(
          destination,
          shorter(properties.getRequestTimeout(), remaining(deadlineNanos)),
          REQUEST_HEADERS,
          properties.getMaxResponseBytes(),
          properties.getAllowedContentTypes());

      if (isRedirect(response.statusCode())) {
        if (redirects >= properties.getMaxRedirects()) {
          throw new LinkPreviewFetchException("TOO_MANY_REDIRECTS");
        }
        var location = response.firstHeader("location");
        if (location == null || location.isBlank()) {
          throw new LinkPreviewFetchException("INVALID_REDIRECT");
        }
        try {
          current = current.resolve(location);
        } catch (IllegalArgumentException failure) {
          throw new LinkPreviewFetchException("INVALID_REDIRECT", failure);
        }
        continue;
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new LinkPreviewFetchException("HTTP_STATUS");
      }
      var rawContentType = response.firstHeader("content-type");
      var contentType = rawContentType == null
          ? ""
          : rawContentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
      if (!properties.getAllowedContentTypes().stream()
          .map(value -> value.toLowerCase(Locale.ROOT))
          .anyMatch(contentType::equals)) {
        throw new LinkPreviewFetchException("CONTENT_TYPE");
      }
      return new FetchedPage(current, contentType, response.body());
    }
  }

  private ApprovedDestination resolveApproved(URI uri, Duration timeout) {
    try {
      return destinationResolver.resolveApproved(uri, timeout);
    } catch (LinkPreviewFetchException failure) {
      throw failure;
    } catch (IllegalArgumentException failure) {
      throw new LinkPreviewFetchException("DESTINATION_REJECTED", failure);
    }
  }

  private Duration remaining(long deadlineNanos) {
    var remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new LinkPreviewFetchException("TIMEOUT");
    }
    return Duration.ofNanos(remaining);
  }

  private Duration shorter(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }

  private boolean isRedirect(int statusCode) {
    return statusCode == 301
        || statusCode == 302
        || statusCode == 303
        || statusCode == 307
        || statusCode == 308;
  }

  @FunctionalInterface
  interface DestinationResolver {
    ApprovedDestination resolveApproved(URI uri, Duration timeout);
  }

  public record FetchedPage(URI finalUri, String contentType, byte[] body) {
    public FetchedPage {
      body = body.clone();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }
}

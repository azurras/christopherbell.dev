package dev.christopherbell.post.preview;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fetches HTML with manual redirects, per-hop policy checks, and bounded reads. */
@Component
public class BoundedLinkPreviewHttpClient {
  private final HttpClient httpClient;
  private final PostLinkPreviewDestinationPolicy destinationPolicy;
  private final PostLinkPreviewProperties properties;

  @Autowired
  public BoundedLinkPreviewHttpClient(
      PostLinkPreviewProperties properties,
      PostLinkPreviewDestinationPolicy destinationPolicy
  ) {
    this(HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(), destinationPolicy, properties);
  }

  public BoundedLinkPreviewHttpClient(
      HttpClient httpClient,
      PostLinkPreviewDestinationPolicy destinationPolicy,
      PostLinkPreviewProperties properties
  ) {
    this.httpClient = httpClient;
    this.destinationPolicy = destinationPolicy;
    this.properties = properties;
  }

  public FetchedPage fetch(URI initialUri) {
    var deadlineNanos = System.nanoTime() + properties.getOverallTimeout().toNanos();
    var current = initialUri;
    for (var redirects = 0; ; redirects++) {
      requirePublic(current, deadlineNanos);
      var remaining = remaining(deadlineNanos);
      var request = HttpRequest.newBuilder(current)
          .GET()
          .timeout(shorter(properties.getRequestTimeout(), remaining))
          .header("Accept", "text/html, application/xhtml+xml")
          .header("User-Agent", "christopherbell.dev link preview fetcher")
          .build();
      final HttpResponse<InputStream> response;
      try {
        response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      } catch (java.net.http.HttpTimeoutException failure) {
        throw new LinkPreviewFetchException("TIMEOUT", failure);
      } catch (IOException failure) {
        throw new LinkPreviewFetchException("REMOTE_IO", failure);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new LinkPreviewFetchException("INTERRUPTED", failure);
      }

      if (isRedirect(response.statusCode())) {
        close(response.body());
        if (redirects >= properties.getMaxRedirects()) {
          throw new LinkPreviewFetchException("TOO_MANY_REDIRECTS");
        }
        var location = response.headers().firstValue("location")
            .orElseThrow(() -> new LinkPreviewFetchException("INVALID_REDIRECT"));
        current = current.resolve(location);
        continue;
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        close(response.body());
        throw new LinkPreviewFetchException("HTTP_STATUS");
      }
      var contentType = response.headers().firstValue("content-type")
          .map(value -> value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT))
          .orElse("");
      if (!properties.getAllowedContentTypes().stream()
          .map(value -> value.toLowerCase(Locale.ROOT))
          .anyMatch(contentType::equals)) {
        close(response.body());
        throw new LinkPreviewFetchException("CONTENT_TYPE");
      }
      response.headers().firstValueAsLong("content-length").ifPresent(length -> {
        if (length > properties.getMaxResponseBytes()) {
          close(response.body());
          throw new LinkPreviewFetchException("RESPONSE_TOO_LARGE");
        }
      });
      var body = readBounded(response.body(), deadlineNanos);
      return new FetchedPage(current, contentType, body);
    }
  }

  private void requirePublic(URI uri, long deadlineNanos) {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    Future<Void> future = null;
    try {
      future = executor.submit(() -> {
        destinationPolicy.requirePublic(uri);
        return null;
      });
      var remaining = remaining(deadlineNanos);
      future.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException failure) {
      throw new LinkPreviewFetchException("TIMEOUT", failure);
    } catch (ExecutionException failure) {
      throw new LinkPreviewFetchException("DESTINATION_REJECTED", failure.getCause());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new LinkPreviewFetchException("INTERRUPTED", failure);
    } finally {
      if (future != null) {
        future.cancel(true);
      }
      executor.shutdownNow();
    }
  }

  private byte[] readBounded(InputStream body, long deadlineNanos) {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    Future<byte[]> future = null;
    try (body) {
      future = executor.submit(() -> body.readNBytes(properties.getMaxResponseBytes() + 1));
      var remaining = remaining(deadlineNanos);
      var bytes = future.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
      if (bytes.length > properties.getMaxResponseBytes()) {
        throw new LinkPreviewFetchException("RESPONSE_TOO_LARGE");
      }
      return bytes;
    } catch (TimeoutException failure) {
      throw new LinkPreviewFetchException("TIMEOUT", failure);
    } catch (ExecutionException failure) {
      throw new LinkPreviewFetchException("REMOTE_IO", failure.getCause());
    } catch (IOException failure) {
      throw new LinkPreviewFetchException("REMOTE_IO", failure);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new LinkPreviewFetchException("INTERRUPTED", failure);
    } finally {
      if (future != null) {
        future.cancel(true);
      }
      executor.shutdownNow();
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

  private void close(InputStream body) {
    try {
      body.close();
    } catch (IOException ignored) {
      // Closing a rejected response is best effort.
    }
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

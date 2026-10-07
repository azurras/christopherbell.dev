package dev.christopherbell.sitemonitor.fetch;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** GET/HEAD only, no proxy/cookies, pinned DNS, verified TLS and same-origin manual redirects. */
@Component
public class PublicMonitorGateway implements MonitorGateway {
  private final SiteMonitorDestinationPolicy destinations;
  private final MonitorHttpTransport transport;
  @Autowired
  public PublicMonitorGateway(SiteMonitorDestinationPolicy destinations) {
    this(destinations, new MonitorHttpTransport(Duration.ofSeconds(2)));
  }
  PublicMonitorGateway(SiteMonitorDestinationPolicy destinations, MonitorHttpTransport transport) {
    this.destinations = destinations;
    this.transport = transport;
  }

  public Result fetch(URI uri, URI origin, long deadlineNanos, boolean headersOnly) {
    return fetch(uri, origin, deadlineNanos, headersOnly, () -> {});
  }

  @Override
  public Result fetch(URI uri, URI origin, long deadlineNanos, boolean headersOnly, Runnable guard) {
    var nextUri = uri;
    for (int redirects = 0; redirects <= 3; redirects++) {
      guard.run();
      if (!MonitorUrls.sameOrigin(origin, nextUri)) {
        throw new MonitorFetchException("REDIRECT_OUTSIDE_SITE");
      }
      try {
        var destination = destinations.resolveApproved(nextUri, remaining(deadlineNanos));
        guard.run();
        var response = transport.get(destination, remaining(deadlineNanos), Map.of(
            "Accept", "text/html, application/xhtml+xml, text/plain",
            "Accept-Encoding", "identity",
            "User-Agent", "christopherbell.dev website monitor (owner-verified pilot)"),
            1_048_576, List.of("text/html", "application/xhtml+xml", "text/plain"), headersOnly);
        if (List.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
          String location = response.firstHeader("location");
          if (location == null || location.length() > 2048) {
            throw new MonitorFetchException("INVALID_REDIRECT");
          }
          nextUri = nextUri.resolve(location);
          continue;
        }
        return new Result(response.statusCode(), nextUri,
            response.firstHeader("content-type"), response.body(), response.firstHeader("x-robots-tag"));
      } catch (IllegalArgumentException failure) {
        throw new MonitorFetchException("DESTINATION_REJECTED", failure);
      }
    }
    throw new MonitorFetchException("TOO_MANY_REDIRECTS");
  }

  private static Duration remaining(long deadlineNanos) {
    long remainingNanos = deadlineNanos - System.nanoTime();
    if (remainingNanos <= 0) throw new MonitorFetchException("TIME_BUDGET");
    return Duration.ofNanos(Math.min(remainingNanos, Duration.ofSeconds(4).toNanos()));
  }
}

package dev.christopherbell.federation.configuration;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded scheduling and allow-list settings for controlled ActivityPub delivery. */
public record FederationOutboundProperties(
    Instant notBefore,
    List<ControlledPeer> peers,
    Duration connectTimeout,
    Duration requestTimeout,
    Duration initialBackoff,
    Duration maxBackoff,
    int maxAttempts,
    int batchSize,
    boolean developmentLoopbackEnabled
) {
  private static final int MAX_PEERS = 20;

  public FederationOutboundProperties {
    peers = peers == null ? List.of() : List.copyOf(peers);
    requireDuration(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(30),
        "connect timeout");
    requireDuration(requestTimeout, connectTimeout, Duration.ofMinutes(1), "request timeout");
    requireDuration(initialBackoff, Duration.ofSeconds(1), Duration.ofHours(1),
        "initial backoff");
    requireDuration(maxBackoff, initialBackoff, Duration.ofHours(24), "maximum backoff");
    requireRange(maxAttempts, 1, 20, "maximum attempts");
    requireRange(batchSize, 1, 100, "batch size");
    if (peers.size() > MAX_PEERS) {
      throw new IllegalArgumentException("Federation outbound peers cannot exceed 20 entries");
    }
    var names = new HashSet<String>();
    for (var peer : peers) {
      Objects.requireNonNull(peer, "Federation outbound peer cannot be null");
      requireBoundedPeerInbox(peer.inbox(), developmentLoopbackEnabled);
      if (!names.add(peer.name())) {
        throw new IllegalArgumentException("Federation outbound peer names must be unique");
      }
    }
  }

  static FederationOutboundProperties defaults() {
    return new FederationOutboundProperties(
        null,
        List.of(),
        Duration.ofSeconds(3),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofHours(6),
        6,
        10,
        false);
  }

  private static void requireDuration(
      Duration value,
      Duration minimum,
      Duration maximum,
      String label
  ) {
    if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(
          "Federation outbound " + label + " is outside its allowed range");
    }
  }

  private static void requireRange(int value, int minimum, int maximum, String label) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          "Federation outbound " + label + " is outside its allowed range");
    }
  }

  private static void requireBoundedPeerInbox(URI inbox, boolean developmentLoopbackEnabled) {
    String scheme = inbox.getScheme() == null
        ? ""
        : inbox.getScheme().toLowerCase(java.util.Locale.ROOT);
    boolean validHttps = "https".equals(scheme)
        && (inbox.getPort() == -1 || inbox.getPort() == 443);
    boolean validDevelopmentHttp = developmentLoopbackEnabled
        && "http".equals(scheme)
        && inbox.getPort() >= 1
        && inbox.getPort() <= 65_535;
    String path = inbox.getRawPath();
    if ((!validHttps && !validDevelopmentHttp)
        || inbox.getHost() == null
        || inbox.getHost().isBlank()
        || inbox.getUserInfo() != null
        || inbox.getRawQuery() != null
        || inbox.getRawFragment() != null
        || path == null
        || path.isBlank()
        || "/".equals(path)) {
      throw new IllegalArgumentException(
          "Federation outbound peer must use a bounded HTTPS inbox URL");
    }
  }

  /** One operator-controlled peer inbox. Network safety is checked immediately before delivery. */
  public record ControlledPeer(String name, URI inbox) {
    private static final int MAX_NAME_LENGTH = 100;

    public ControlledPeer {
      if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
        throw new IllegalArgumentException(
            "Federation outbound peer name must contain between 1 and 100 characters");
      }
      Objects.requireNonNull(inbox, "Federation outbound peer inbox is required");
    }
  }
}

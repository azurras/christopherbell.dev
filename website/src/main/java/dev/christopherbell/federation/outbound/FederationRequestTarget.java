package dev.christopherbell.federation.outbound;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Canonical HTTP authority and request-target strings shared by signing and transport. */
public record FederationRequestTarget(URI inbox) {
  public FederationRequestTarget {
    Objects.requireNonNull(inbox, "inbox");
    String scheme = normalize(inbox.getScheme());
    if (!("http".equals(scheme) || "https".equals(scheme))
        || inbox.getHost() == null
        || inbox.getHost().isBlank()
        || inbox.getUserInfo() != null
        || inbox.getFragment() != null
        || inbox.getPort() < -1
        || inbox.getPort() > 65_535) {
      throw new IllegalArgumentException("Federation inbox must be an absolute HTTP(S) URI");
    }
  }

  public String hostHeader() {
    String host = inbox.getHost();
    if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
      host = "[" + host + "]";
    }
    int port = inbox.getPort();
    boolean defaultPort = port == -1
        || ("https".equals(normalize(inbox.getScheme())) && port == 443)
        || ("http".equals(normalize(inbox.getScheme())) && port == 80);
    return defaultPort ? host.toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT) + ":" + port;
  }

  public String requestTarget() {
    String path = inbox.getRawPath();
    String target = path == null || path.isEmpty() ? "/" : path;
    return inbox.getRawQuery() == null ? target : target + "?" + inbox.getRawQuery();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }
}

package dev.christopherbell.federation.outbound;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable headers and exact serialized bytes for one signed ActivityPub request. */
public final class SignedFederationRequest {
  private static final Set<String> TRANSPORT_HEADERS = Set.of(
      "connection", "content-length", "host", "proxy-authorization", "transfer-encoding");

  private final Map<String, String> headers;
  private final byte[] body;

  public SignedFederationRequest(Map<String, String> headers, byte[] body) {
    Objects.requireNonNull(headers, "headers");
    var copiedHeaders = new LinkedHashMap<String, String>();
    headers.forEach((name, value) -> {
      requireSafeHeader(name, value);
      copiedHeaders.put(name, value);
    });
    this.headers = Map.copyOf(copiedHeaders);
    this.body = Objects.requireNonNull(body, "body").clone();
  }

  public Map<String, String> headers() {
    return headers;
  }

  public byte[] body() {
    return body.clone();
  }

  private static void requireSafeHeader(String name, String value) {
    if (name == null
        || name.isBlank()
        || value == null
        || name.indexOf('\r') >= 0
        || name.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0
        || value.indexOf('\n') >= 0
        || TRANSPORT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Signed federation request contains an unsafe header");
    }
  }
}

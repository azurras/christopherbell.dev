package dev.christopherbell.sitemonitor.fetch;

import java.net.URI;

/** Fetch boundary with a monotonic run deadline and exact origin containment. */
public interface MonitorGateway {
  Result fetch(URI uri, URI origin, long deadlineNanos, boolean headersOnly);
  default Result fetch(URI uri, URI origin, long deadlineNanos, boolean headersOnly, Runnable guard) {
    guard.run();
    return fetch(uri, origin, deadlineNanos, headersOnly);
  }
  record Result(int status, URI finalUri, String contentType, byte[] body, String robotsHeader) {
    public Result { body = body.clone(); }
    public Result(int status, URI finalUri, String contentType, byte[] body) {
      this(status, finalUri, contentType, body, "");
    }
    @Override public byte[] body() { return body.clone(); }
  }
}

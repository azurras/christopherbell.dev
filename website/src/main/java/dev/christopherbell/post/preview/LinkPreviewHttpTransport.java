package dev.christopherbell.post.preview;

import dev.christopherbell.post.preview.PostLinkPreviewDestinationPolicy.ApprovedDestination;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SNIHostName;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientSecurityUtils;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider.ProtocolSslContextSpec;

/** Opens one fresh, non-retrying connection to an already-approved preview address. */
final class LinkPreviewHttpTransport {
  private final HttpClient baseClient;
  private final ProtocolSslContextSpec tlsContext;

  LinkPreviewHttpTransport(Duration connectTimeout) {
    this(connectTimeout, Http11SslContextSpec.forClient());
  }

  LinkPreviewHttpTransport(Duration connectTimeout, ProtocolSslContextSpec tlsContext) {
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    if (connectTimeout.isZero() || connectTimeout.isNegative()) {
      throw new IllegalArgumentException("connectTimeout must be positive");
    }
    this.tlsContext = Objects.requireNonNull(tlsContext, "tlsContext");
    this.baseClient = HttpClient.create(ConnectionProvider.newConnection())
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
        .followRedirect(false)
        .disableRetry(true)
        .noProxy();
  }

  Response get(
      ApprovedDestination destination,
      Duration timeout,
      Map<String, String> requestHeaders,
      int maxBodyBytes,
      List<String> allowedContentTypes
  ) {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(requestHeaders, "requestHeaders");
    Objects.requireNonNull(allowedContentTypes, "allowedContentTypes");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new LinkPreviewFetchException("TIMEOUT");
    }
    if (maxBodyBytes < 0) {
      throw new IllegalArgumentException("maxBodyBytes must not be negative");
    }

    HttpClient client = baseClient
        .remoteAddress(destination::remoteAddress)
        .responseTimeout(timeout);
    if ("https".equalsIgnoreCase(destination.uri().getScheme())) {
      client = client.secure(spec -> spec.sslContext(tlsContext)
          .handlerConfigurator(HttpClientSecurityUtils.HOSTNAME_VERIFICATION_CONFIGURER)
          .serverNames(new SNIHostName(destination.originalHost())));
    }

    try {
      var response = client
          .headers(headers -> {
            requestHeaders.forEach(headers::set);
            headers.set(HttpHeaderNames.HOST, hostHeader(destination));
          })
          .get()
          .uri(requestTarget(destination.uri()))
          .response((inbound, body) -> {
            var metadata = new Response(
                inbound.status().code(), copyHeaders(inbound.responseHeaders()), new byte[0]);
            if (inbound.status().code() < 200 || inbound.status().code() >= 300) {
              return Mono.just(metadata);
            }
            if (!isAllowedContentType(
                inbound.responseHeaders().get(HttpHeaderNames.CONTENT_TYPE), allowedContentTypes)) {
              return Mono.error(new LinkPreviewFetchException("CONTENT_TYPE"));
            }
            var contentLength = inbound.responseHeaders().get(HttpHeaderNames.CONTENT_LENGTH);
            if (contentLength != null && declaredLengthExceeds(contentLength, maxBodyBytes)) {
              return Mono.error(new LinkPreviewFetchException("RESPONSE_TOO_LARGE"));
            }
            return boundedBody(body, maxBodyBytes)
                .map(bytes -> new Response(metadata.statusCode(), metadata.headers(), bytes));
          })
          .next()
          .block(timeout);
      if (response == null) {
        throw new LinkPreviewFetchException("REMOTE_IO");
      }
      return response;
    } catch (RuntimeException failure) {
      var interruptedFailure = findCause(failure, InterruptedException.class);
      if (interruptedFailure != null) {
        Thread.currentThread().interrupt();
        throw new LinkPreviewFetchException("INTERRUPTED", interruptedFailure);
      }
      var fetchFailure = findCause(failure, LinkPreviewFetchException.class);
      if (fetchFailure != null) {
        throw fetchFailure;
      }
      if (isTimeout(failure)) {
        throw new LinkPreviewFetchException("TIMEOUT", failure);
      }
      throw new LinkPreviewFetchException("REMOTE_IO", failure);
    }
  }

  private Mono<byte[]> boundedBody(reactor.netty.ByteBufFlux body, int maxBodyBytes) {
    var output = new ByteArrayOutputStream(Math.min(maxBodyBytes, 8192));
    return body.handle((buffer, sink) -> {
      var readable = buffer.readableBytes();
      if (readable > maxBodyBytes - output.size()) {
        sink.error(new LinkPreviewFetchException("RESPONSE_TOO_LARGE"));
        return;
      }
      var bytes = new byte[readable];
      buffer.getBytes(buffer.readerIndex(), bytes);
      output.writeBytes(bytes);
    }).then(Mono.fromSupplier(output::toByteArray));
  }

  private static boolean declaredLengthExceeds(String value, int maxBodyBytes) {
    try {
      return Long.parseLong(value) > maxBodyBytes;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static boolean isAllowedContentType(String value, List<String> allowedContentTypes) {
    if (value == null) return false;
    var contentType = value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return allowedContentTypes.stream()
        .map(allowed -> allowed.toLowerCase(Locale.ROOT))
        .anyMatch(contentType::equals);
  }

  private static Map<String, List<String>> copyHeaders(io.netty.handler.codec.http.HttpHeaders source) {
    var copied = new LinkedHashMap<String, List<String>>();
    source.forEach(entry -> copied
        .computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
        .add(entry.getValue()));
    copied.replaceAll((ignored, values) -> List.copyOf(values));
    return Map.copyOf(copied);
  }

  private static String hostHeader(ApprovedDestination destination) {
    var host = destination.originalHost().contains(":")
        ? "[" + destination.originalHost() + "]"
        : destination.originalHost();
    var port = destination.remoteAddress().getPort();
    var defaultPort = "https".equalsIgnoreCase(destination.uri().getScheme()) ? 443 : 80;
    return port == defaultPort ? host : host + ":" + port;
  }

  private static String requestTarget(java.net.URI uri) {
    var path = uri.getRawPath();
    var target = path == null || path.isBlank() ? "/" : path;
    return uri.getRawQuery() == null ? target : target + "?" + uri.getRawQuery();
  }

  private static boolean isTimeout(Throwable failure) {
    for (var current = failure; current != null; current = current.getCause()) {
      if (current instanceof TimeoutException
          || current instanceof SocketTimeoutException
          || current instanceof ReadTimeoutException
          || current.getClass().getSimpleName().contains("Timeout")) {
        return true;
      }
    }
    return false;
  }

  private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
    for (var current = Exceptions.unwrap(failure); current != null; current = current.getCause()) {
      if (type.isInstance(current)) return type.cast(current);
    }
    return null;
  }

  record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
    Response {
      headers = Map.copyOf(headers);
      body = body.clone();
    }

    String firstHeader(String name) {
      var values = headers.get(name.toLowerCase(Locale.ROOT));
      return values == null || values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public byte[] body() {
      return body.clone();
    }
  }
}

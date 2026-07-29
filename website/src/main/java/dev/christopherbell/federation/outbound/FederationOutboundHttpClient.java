package dev.christopherbell.federation.outbound;

import dev.christopherbell.federation.outbound.FederationPeerAddressPolicy.ValidatedPeerTarget;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import javax.net.ssl.SNIHostName;
import reactor.core.publisher.Mono;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientSecurityUtils;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider.ProtocolSslContextSpec;

/** Sends one bounded signed activity to one already-validated controlled inbox. */
final class FederationOutboundHttpClient {
  private final HttpClient baseClient;
  private final Duration requestTimeout;
  private final Clock clock;
  private final ProtocolSslContextSpec tlsContext;

  FederationOutboundHttpClient(Duration connectTimeout, Duration requestTimeout, Clock clock) {
    this(connectTimeout, requestTimeout, clock, Http11SslContextSpec.forClient());
  }

  FederationOutboundHttpClient(
      Duration connectTimeout,
      Duration requestTimeout,
      Clock clock,
      ProtocolSslContextSpec tlsContext
  ) {
    this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.tlsContext = Objects.requireNonNull(tlsContext, "tlsContext");
    Duration boundedConnectTimeout = requirePositive(connectTimeout, "connectTimeout");
    this.baseClient = HttpClient.create(ConnectionProvider.newConnection())
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(boundedConnectTimeout.toMillis()))
        .responseTimeout(requestTimeout)
        .followRedirect(false)
        .disableRetry(true)
        .noProxy();
  }

  FederationDeliveryResult post(ValidatedPeerTarget target, SignedFederationRequest request) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(request, "request");
    HttpClient client = baseClient.remoteAddress(target::remoteAddress);
    if ("https".equalsIgnoreCase(target.inbox().getScheme())) {
      client = client.secure(spec -> spec.sslContext(tlsContext)
          .handlerConfigurator(HttpClientSecurityUtils.HOSTNAME_VERIFICATION_CONFIGURER)
          .serverNames(new SNIHostName(target.originalHost())));
    }
    byte[] body = request.body();
    try {
      FederationDeliveryResult result = client
          .headers(headers -> {
            request.headers().forEach(headers::set);
            headers.set(HttpHeaderNames.HOST, hostHeader(target));
          })
          .post()
          .uri(target.inbox().getRawPath())
          .send((ignored, outbound) -> outbound.sendByteArray(Mono.just(body)))
          .response((response, responseBody) -> responseBody
              .then(Mono.fromSupplier(() -> classify(
                  response.status().code(), response.responseHeaders().get("Retry-After")))))
          .next()
          .block(requestTimeout.plusSeconds(1));
      return result == null
          ? new FederationDeliveryResult.RetryableFailure(
              OptionalInt.empty(), Optional.empty())
          : result;
    } catch (RuntimeException failure) {
      return new FederationDeliveryResult.RetryableFailure(
          OptionalInt.empty(), Optional.empty());
    }
  }

  private FederationDeliveryResult classify(int statusCode, String retryAfter) {
    if (statusCode >= 200 && statusCode <= 299) {
      return new FederationDeliveryResult.Delivered(statusCode);
    }
    if (statusCode == 408
        || statusCode == 425
        || statusCode == 429
        || statusCode >= 500) {
      return new FederationDeliveryResult.RetryableFailure(
          OptionalInt.of(statusCode), parseRetryAfter(retryAfter));
    }
    return new FederationDeliveryResult.PermanentFailure(statusCode);
  }

  private Optional<Duration> parseRetryAfter(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      long seconds = Long.parseLong(value.strip());
      return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
    } catch (NumberFormatException ignored) {
      try {
        Duration duration = Duration.between(
            clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
      } catch (DateTimeParseException invalidDate) {
        return Optional.empty();
      }
    }
  }

  private static String hostHeader(ValidatedPeerTarget target) {
    int port = target.inbox().getPort();
    boolean defaultPort = port == -1
        || ("https".equalsIgnoreCase(target.inbox().getScheme()) && port == 443)
        || ("http".equalsIgnoreCase(target.inbox().getScheme()) && port == 80);
    return defaultPort ? target.originalHost() : target.originalHost() + ":" + port;
  }

  private static Duration requirePositive(Duration value, String label) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return value;
  }
}

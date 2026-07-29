package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.server.HttpServer;

class FederationOutboundHttpClientTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
  private static final char[] KEYSTORE_PASSWORD = "test-password".toCharArray();
  private final List<DisposableServer> servers = new ArrayList<>();
  @TempDir Path tempDirectory;

  @AfterEach
  void stopServers() {
    servers.forEach(DisposableServer::disposeNow);
  }

  @Test
  void postsExactBytesToPinnedTlsAddressWithOriginalHostIdentity() throws Exception {
    var receivedHost = new AtomicReference<String>();
    var receivedBody = new AtomicReference<String>();
    var peerKeyManager = createPeerKeyManager(tempDirectory);
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .secure(spec -> spec.sslContext(Http11SslContextSpec.forServer(peerKeyManager)))
        .handle((request, response) -> {
          receivedHost.set(request.requestHeaders().get("Host"));
          return request.receive().aggregate().asString(StandardCharsets.UTF_8)
              .flatMap(body -> {
                receivedBody.set(body);
                return response.status(202).sendString(Mono.just("ignored response body")).then();
              });
        })
        .bindNow());
    var clientTls = Http11SslContextSpec.forClient()
        .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
    var client = new FederationOutboundHttpClient(
        Duration.ofSeconds(2), Duration.ofSeconds(2), Clock.fixed(NOW, ZoneOffset.UTC), clientTls);
    byte[] body = "{\"type\":\"Create\"}".getBytes(StandardCharsets.UTF_8);
    var request = new SignedFederationRequest(Map.of(
        "Content-Type", "application/activity+json",
        "Date", "Tue, 28 Jul 2026 12:00:00 GMT",
        "Digest", "SHA-256=test",
        "Signature", "keyId=\"test\""), body);
    body[0] = 'X';

    var result = client.post(target(
        "https://peer.example:" + server.port() + "/inbox", "peer.example", server.port()),
        request);

    assertThat(result).isEqualTo(new FederationDeliveryResult.Delivered(202));
    assertThat(receivedHost).hasValue("peer.example:" + server.port());
    assertThat(receivedBody).hasValue("{\"type\":\"Create\"}");
  }

  @Test
  void doesNotFollowRedirects() throws Exception {
    var followed = new AtomicInteger();
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .route(routes -> routes
            .post("/redirect", (request, response) -> response.status(307)
                .header("Location", "/followed").send())
            .post("/followed", (request, response) -> {
              followed.incrementAndGet();
              return response.status(202).send();
            }))
        .bindNow());
    var client = client(Duration.ofSeconds(2));

    var result = client.post(target(
        "http://peer.example:" + server.port() + "/redirect", "peer.example", server.port()),
        request());

    assertThat(result).isEqualTo(new FederationDeliveryResult.PermanentFailure(307));
    assertThat(followed).hasValue(0);
  }

  @Test
  void classifiesRetryableAndPermanentResponsesWithoutReturningTheirBodies() throws Exception {
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .route(routes -> routes
            .post("/rate-limited", (request, response) -> response.status(429)
                .header("Retry-After", "120").sendString(Mono.just("do not retain me")))
            .post("/failed", (request, response) -> response.status(503)
                .sendString(Mono.just("do not retain me")))
            .post("/rejected", (request, response) -> response.status(400)
                .sendString(Mono.just("do not retain me"))))
        .bindNow());
    var client = client(Duration.ofSeconds(2));

    assertThat(client.post(target(
        "http://peer.example:" + server.port() + "/rate-limited",
        "peer.example",
        server.port()), request()))
        .isEqualTo(new FederationDeliveryResult.RetryableFailure(
            java.util.OptionalInt.of(429), java.util.Optional.of(Duration.ofSeconds(120))));
    assertThat(client.post(target(
        "http://peer.example:" + server.port() + "/failed",
        "peer.example",
        server.port()), request()))
        .isEqualTo(new FederationDeliveryResult.RetryableFailure(
            java.util.OptionalInt.of(503), java.util.Optional.empty()));
    assertThat(client.post(target(
        "http://peer.example:" + server.port() + "/rejected",
        "peer.example",
        server.port()), request()))
        .isEqualTo(new FederationDeliveryResult.PermanentFailure(400));
  }

  @Test
  void turnsResponseTimeoutIntoOneRetryableFailure() throws Exception {
    var attempts = new AtomicInteger();
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> {
          attempts.incrementAndGet();
          return Mono.never();
        })
        .bindNow());
    var client = client(Duration.ofMillis(100));

    var result = client.post(target(
        "http://peer.example:" + server.port() + "/timeout", "peer.example", server.port()),
        request());

    assertThat(result).isEqualTo(new FederationDeliveryResult.RetryableFailure(
        java.util.OptionalInt.empty(), java.util.Optional.empty()));
    assertThat(attempts).hasValue(1);
  }

  private FederationOutboundHttpClient client(Duration requestTimeout) {
    return new FederationOutboundHttpClient(
        Duration.ofSeconds(2),
        requestTimeout,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Http11SslContextSpec.forClient());
  }

  private SignedFederationRequest request() {
    return new SignedFederationRequest(
        Map.of("Content-Type", "application/activity+json"),
        "{}".getBytes(StandardCharsets.UTF_8));
  }

  private FederationPeerAddressPolicy.ValidatedPeerTarget target(
      String inbox,
      String originalHost,
      int port
  ) throws Exception {
    return new FederationPeerAddressPolicy.ValidatedPeerTarget(
        URI.create(inbox),
        originalHost,
        new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
  }

  private DisposableServer remember(DisposableServer server) {
    servers.add(server);
    return server;
  }

  private static KeyManagerFactory createPeerKeyManager(Path directory)
      throws IOException, GeneralSecurityException, InterruptedException {
    Path keyStorePath = directory.resolve("peer.p12");
    Path keytool = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
    var process = new ProcessBuilder(
        keytool.toString(),
        "-genkeypair",
        "-alias", "peer",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "2",
        "-dname", "CN=peer.example",
        "-ext", "SAN=dns:peer.example",
        "-storetype", "PKCS12",
        "-keystore", keyStorePath.toString(),
        "-storepass", new String(KEYSTORE_PASSWORD),
        "-keypass", new String(KEYSTORE_PASSWORD),
        "-noprompt")
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) {
      throw new IllegalStateException("keytool failed to create the TLS test identity: " + output);
    }
    var keyStore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(keyStorePath)) {
      keyStore.load(input, KEYSTORE_PASSWORD);
    }
    var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD);
    return keyManagerFactory;
  }
}

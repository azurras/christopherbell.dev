package dev.christopherbell.post.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.server.HttpServer;

class BoundedLinkPreviewHttpClientTest {
  private static final char[] KEYSTORE_PASSWORD = "test-password".toCharArray();
  private final List<DisposableServer> servers = new ArrayList<>();
  @TempDir Path tempDirectory;

  @AfterEach
  void stopServers() {
    servers.forEach(DisposableServer::disposeNow);
  }

  @Test
  void pinsTheApprovedAddressWhenASecondDnsAnswerWouldChange() throws Exception {
    var receivedHost = new AtomicReference<String>();
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> {
          receivedHost.set(request.requestHeaders().get("Host"));
          return response.status(200)
              .header("Content-Type", "text/html")
              .sendString(Mono.just("<html><title>Bound</title></html>"));
        })
        .bindNow());
    var uri = URI.create("http://answer-changes.invalid:" + server.port() + "/page");
    var resolutions = new AtomicInteger();
    var resolver = resolver((requested, timeout) -> {
      assertThat(requested).isEqualTo(uri);
      assertThat(timeout).isPositive();
      if (resolutions.incrementAndGet() != 1) {
        throw new AssertionError("the destination was resolved more than once for one hop");
      }
      return approved(uri, server.port());
    });
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)), resolver, properties(1024));

    var fetched = client.fetch(uri);

    assertThat(fetched.finalUri()).isEqualTo(uri);
    assertThat(new String(fetched.body(), StandardCharsets.UTF_8)).contains("Bound");
    assertThat(receivedHost).hasValue("answer-changes.invalid:" + server.port());
    assertThat(resolutions).hasValue(1);
  }

  @Test
  void preservesOriginalHostSniAndTlsHostnameVerification() throws Exception {
    var receivedHost = new AtomicReference<String>();
    var receivedSni = new AtomicReference<String>();
    var tls = createTlsMaterial(tempDirectory, "preview.example");
    var serverTls = Http11SslContextSpec.forServer(tls.keyManager())
        .configure(builder -> builder.sslProvider(SslProvider.JDK));
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .secure(spec -> spec.sslContext(serverTls))
        .handle((request, response) -> {
          receivedHost.set(request.requestHeaders().get("Host"));
          request.withConnection(connection -> {
            var sslHandler = connection.channel().pipeline().get(SslHandler.class);
            if (sslHandler.engine().getSession() instanceof ExtendedSSLSession session) {
              session.getRequestedServerNames().stream()
                  .filter(SNIHostName.class::isInstance)
                  .map(SNIHostName.class::cast)
                  .map(SNIHostName::getAsciiName)
                  .findFirst()
                  .ifPresent(receivedSni::set);
            }
          });
          return response.status(200)
              .header("Content-Type", "text/html")
              .sendString(Mono.just("<html><title>TLS</title></html>"));
        })
        .bindNow());
    var clientTls = Http11SslContextSpec.forClient()
        .configure(builder -> builder
            .sslProvider(SslProvider.JDK)
            .trustManager(tls.trustManager()));
    var uri = URI.create("https://preview.example:" + server.port() + "/page");
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2), clientTls),
        resolver((requested, timeout) -> approved(requested, server.port())),
        properties(1024));

    var fetched = client.fetch(uri);

    assertThat(fetched.finalUri()).isEqualTo(uri);
    assertThat(receivedHost).hasValue("preview.example:" + server.port());
    assertThat(receivedSni).hasValue("preview.example");
  }

  @Test
  void rejectsTlsCertificateForAHostnameOtherThanTheOriginalUri() throws Exception {
    var tls = createTlsMaterial(tempDirectory, "certificate.example");
    var serverTls = Http11SslContextSpec.forServer(tls.keyManager())
        .configure(builder -> builder.sslProvider(SslProvider.JDK));
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .secure(spec -> spec.sslContext(serverTls))
        .handle((request, response) -> response.status(200)
            .header("Content-Type", "text/html")
            .sendString(Mono.just("<html></html>")))
        .bindNow());
    var clientTls = Http11SslContextSpec.forClient()
        .configure(builder -> builder
            .sslProvider(SslProvider.JDK)
            .trustManager(tls.trustManager()));
    var uri = URI.create("https://different.example:" + server.port() + "/page");
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2), clientTls),
        resolver((requested, timeout) -> approved(requested, server.port())),
        properties(1024));

    assertThatThrownBy(() -> client.fetch(uri))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("REMOTE_IO");
  }

  @Test
  void categorizesUnsupportedTlsHostIdentitiesAsDestinationRejections() throws Exception {
    var policy = new PostLinkPreviewDestinationPolicy(
        host -> List.of(InetAddress.getByName("1.1.1.1")));
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)),
        policy::resolveApproved,
        properties(1024));

    for (var uri : List.of(
        URI.create("https://public.example./"),
        URI.create("https://[2606:4700:4700::1111]/"))) {
      assertThatThrownBy(() -> client.fetch(uri))
          .isInstanceOf(LinkPreviewFetchException.class)
          .extracting("category")
          .isEqualTo("DESTINATION_REJECTED");
    }
  }

  @Test
  void revalidatesAndPinsEveryManualRedirect() throws Exception {
    var secondHost = new AtomicReference<String>();
    var destination = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> {
          secondHost.set(request.requestHeaders().get("Host"));
          return response.status(200)
              .header("Content-Type", "text/html")
              .sendString(Mono.just("<html><title>Final</title></html>"));
        })
        .bindNow());
    var initial = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> response.status(302)
            .header("Location", "http://redirect-target.invalid:" + destination.port() + "/final")
            .send())
        .bindNow());
    var initialUri = URI.create("http://redirect-source.invalid:" + initial.port() + "/start");
    var finalUri = URI.create(
        "http://redirect-target.invalid:" + destination.port() + "/final");
    var resolved = new ArrayList<URI>();
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)),
        resolver((uri, timeout) -> {
          resolved.add(uri);
          return approved(uri, uri.equals(initialUri) ? initial.port() : destination.port());
        }),
        properties(1024));

    var fetched = client.fetch(initialUri);

    assertThat(fetched.finalUri()).isEqualTo(finalUri);
    assertThat(resolved).containsExactly(initialUri, finalUri);
    assertThat(secondHost).hasValue("redirect-target.invalid:" + destination.port());
  }

  @Test
  void rejectsAnUnsafeRedirectBeforeOpeningItsConnection() throws Exception {
    var unsafeConnections = new AtomicInteger();
    var unsafe = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> {
          unsafeConnections.incrementAndGet();
          return response.status(200).send();
        })
        .bindNow());
    var initial = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> response.status(302)
            .header("Location", "http://unsafe.invalid:" + unsafe.port() + "/internal")
            .send())
        .bindNow());
    var initialUri = URI.create("http://redirect-source.invalid:" + initial.port() + "/start");
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)),
        resolver((uri, timeout) -> {
          if (uri.equals(initialUri)) return approved(uri, initial.port());
          throw new IllegalArgumentException("unsafe DNS answer");
        }),
        properties(1024));

    assertThatThrownBy(() -> client.fetch(initialUri))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("DESTINATION_REJECTED");
    assertThat(unsafeConnections).hasValue(0);
  }

  @Test
  void rejectsAStreamedBodyAboveTheConfiguredByteLimit() throws Exception {
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> response.status(200)
            .header("Content-Type", "text/html")
            .sendString(Mono.just("12345")))
        .bindNow());
    var uri = URI.create("http://bounded.invalid:" + server.port() + "/page");
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)),
        resolver((requested, timeout) -> approved(requested, server.port())),
        properties(4));

    assertThatThrownBy(() -> client.fetch(uri))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("RESPONSE_TOO_LARGE");
  }

  @Test
  void overallTimeoutBoundsAResponseThatNeverCompletes() throws Exception {
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> response.status(200)
            .header("Content-Type", "text/html")
            .sendString(Mono.never()))
        .bindNow());
    var uri = URI.create("http://slow.invalid:" + server.port() + "/page");
    var limits = properties(1024);
    limits.setOverallTimeout(Duration.ofMillis(100));
    limits.setRequestTimeout(Duration.ofMillis(100));
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)),
        resolver((requested, timeout) -> approved(requested, server.port())),
        limits);
    var started = System.nanoTime();

    assertThatThrownBy(() -> client.fetch(uri))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("TIMEOUT");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void categorizesWrappedInterruptionAndRestoresTheWorkerInterruptFlag() throws Exception {
    var requestStarted = new CountDownLatch(1);
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> {
          requestStarted.countDown();
          response.status(200).header("Content-Type", "text/html");
          return response.sendHeaders().then(Mono.never());
        })
        .bindNow());
    var uri = URI.create("http://interrupted.invalid:" + server.port() + "/page");
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)),
        resolver((requested, timeout) -> approved(requested, server.port())),
        properties(1024));
    var failure = new AtomicReference<Throwable>();
    var interrupted = new AtomicBoolean();
    var worker = Thread.ofVirtual().start(() -> {
      try {
        client.fetch(uri);
      } catch (Throwable caught) {
        failure.set(caught);
        interrupted.set(Thread.currentThread().isInterrupted());
      }
    });

    assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
    worker.interrupt();
    worker.join(1_000);

    assertThat(worker.isAlive()).isFalse();
    assertThat(failure.get())
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("INTERRUPTED");
    assertThat(interrupted).isTrue();
  }

  @Test
  void rejectsADisallowedMediaTypeBeforeReadingItsBody() throws Exception {
    var server = remember(HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> {
          response.status(200).header("Content-Type", "application/octet-stream");
          return response.sendHeaders().then(Mono.never());
        })
        .bindNow());
    var uri = URI.create("http://wrong-type.invalid:" + server.port() + "/page");
    var limits = properties(1024);
    limits.setOverallTimeout(Duration.ofMillis(500));
    limits.setRequestTimeout(Duration.ofMillis(500));
    var client = new BoundedLinkPreviewHttpClient(
        new LinkPreviewHttpTransport(Duration.ofSeconds(2)),
        resolver((requested, timeout) -> approved(requested, server.port())),
        limits);

    assertThatThrownBy(() -> client.fetch(uri))
        .isInstanceOf(LinkPreviewFetchException.class)
        .extracting("category")
        .isEqualTo("CONTENT_TYPE");
  }

  private BoundedLinkPreviewHttpClient.DestinationResolver resolver(
      BoundedLinkPreviewHttpClient.DestinationResolver resolver) {
    return resolver;
  }

  private PostLinkPreviewDestinationPolicy.ApprovedDestination approved(URI uri, int port) {
    var address = InetAddress.getLoopbackAddress();
    return new PostLinkPreviewDestinationPolicy.ApprovedDestination(
        uri, uri.getHost(), List.of(address), new InetSocketAddress(address, port));
  }

  private PostLinkPreviewProperties properties(int maxBytes) {
    var properties = new PostLinkPreviewProperties();
    properties.setConnectTimeout(Duration.ofSeconds(2));
    properties.setRequestTimeout(Duration.ofSeconds(3));
    properties.setOverallTimeout(Duration.ofSeconds(5));
    properties.setMaxRedirects(3);
    properties.setMaxResponseBytes(maxBytes);
    properties.setAllowedContentTypes(List.of("text/html", "application/xhtml+xml"));
    return properties;
  }

  private DisposableServer remember(DisposableServer server) {
    servers.add(server);
    return server;
  }

  private static TlsMaterial createTlsMaterial(Path directory, String host)
      throws IOException, GeneralSecurityException, InterruptedException {
    Path keyStorePath = directory.resolve(host + ".p12");
    Path keytool = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
    var process = new ProcessBuilder(
        keytool.toString(),
        "-genkeypair",
        "-alias", "preview",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "2",
        "-dname", "CN=" + host,
        "-ext", "SAN=dns:" + host,
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
    var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);
    return new TlsMaterial(keyManagerFactory, trustManagerFactory);
  }

  private record TlsMaterial(
      KeyManagerFactory keyManager,
      TrustManagerFactory trustManager
  ) {}
}

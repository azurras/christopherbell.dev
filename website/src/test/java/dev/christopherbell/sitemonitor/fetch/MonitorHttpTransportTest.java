package dev.christopherbell.sitemonitor.fetch;

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

class MonitorHttpTransportTest {
  private static final char[] KEYSTORE_PASSWORD = "test-password".toCharArray();
  private final List<DisposableServer> servers = new ArrayList<>();
  @TempDir Path tempDirectory;
  @AfterEach void stopServers() { servers.forEach(DisposableServer::disposeNow); }
  private SiteMonitorDestinationPolicy.ApprovedDestination approved(URI uri, int port) {
    var address = InetAddress.getLoopbackAddress();
    return new SiteMonitorDestinationPolicy.ApprovedDestination(uri, uri.getHost(), List.of(address),
        new InetSocketAddress(address, port));
  }
  @Test void pinnedTlsPreservesHostSniAndRejectsWrongCertificateHostname() throws Exception {
    var host = new AtomicReference<String>(); var sni = new AtomicReference<String>();
    var tls = createTlsMaterial(tempDirectory, "monitor.example");
    var server = HttpServer.create().host("127.0.0.1").port(0)
        .secure(spec -> spec.sslContext(Http11SslContextSpec.forServer(tls.keyManager())
            .configure(builder -> builder.sslProvider(SslProvider.JDK))))
        .handle((request, response) -> {
          host.set(request.requestHeaders().get("Host"));
          request.withConnection(connection -> {
            var handler = connection.channel().pipeline().get(SslHandler.class);
            if (handler.engine().getSession() instanceof ExtendedSSLSession session) {
              session.getRequestedServerNames().stream().filter(SNIHostName.class::isInstance)
                  .map(SNIHostName.class::cast).map(SNIHostName::getAsciiName).findFirst().ifPresent(sni::set);
            }
          });
          return response.header("Content-Type", "text/html").sendString(Mono.just("<title>TLS</title>"));
        }).bindNow();
    servers.add(server);
    var client = new MonitorHttpTransport(Duration.ofSeconds(1), Http11SslContextSpec.forClient()
        .configure(builder -> builder.sslProvider(SslProvider.JDK).trustManager(tls.trustManager())));
    var uri = URI.create("https://monitor.example:" + server.port() + "/");
    assertThat(new String(client.get(approved(uri, server.port()), Duration.ofSeconds(3), Map.of(),
        1024, List.of("text/html"), false).body(), StandardCharsets.UTF_8)).contains("TLS");
    assertThat(host).hasValue("monitor.example:" + server.port());
    assertThat(sni).hasValue("monitor.example");
    var wrong = URI.create("https://different.example:" + server.port() + "/");
    assertThatThrownBy(() -> client.get(approved(wrong, server.port()), Duration.ofSeconds(3), Map.of(),
        1024, List.of("text/html"), false)).isInstanceOf(MonitorFetchException.class)
        .extracting("category").isEqualTo("REMOTE_IO");
  }
  @Test void getRejectsOversizeAndStallsWhileHeadDoesNotDownloadAnAsset() throws Exception {
    var method = new AtomicReference<String>();
    var server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
      method.set(request.method().name());
      if (request.uri().equals("/stall")) return response.header("Content-Type", "text/html").sendString(Mono.never());
      return response.header("Content-Type", "application/octet-stream")
          .header("Content-Length", "2048").sendString(Mono.just("x".repeat(2048)));
    }).bindNow(); servers.add(server);
    var client = new MonitorHttpTransport(Duration.ofSeconds(1));
    var uri = URI.create("http://pinned.invalid:" + server.port() + "/asset");
    var response = client.get(approved(uri, server.port()), Duration.ofSeconds(1), Map.of(),
        1024, List.of("text/html"), true);
    assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).isEmpty();
    assertThat(method).hasValue("HEAD");
    assertThatThrownBy(() -> client.get(approved(uri, server.port()), Duration.ofSeconds(1), Map.of(),
        1024, List.of("application/octet-stream"), false)).isInstanceOf(MonitorFetchException.class)
        .extracting("category").isEqualTo("RESPONSE_TOO_LARGE");
    var stall = URI.create("http://pinned.invalid:" + server.port() + "/stall");
    assertThatThrownBy(() -> client.get(approved(stall, server.port()), Duration.ofMillis(150), Map.of(),
        1024, List.of("text/html"), false)).isInstanceOf(MonitorFetchException.class)
        .extracting("category").isEqualTo("TIMEOUT");
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

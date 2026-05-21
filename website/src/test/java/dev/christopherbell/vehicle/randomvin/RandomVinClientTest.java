package dev.christopherbell.vehicle.randomvin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.vehicle.model.VehicleProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RandomVinClientTest {
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("Get VIN returns response body for successful status")
  void getVin_whenServerReturnsSuccess_returnsBody() throws Exception {
    startServer(200, "1HGCM82633A004352");
    var client = new RandomVinClient(properties(serverUrl()));

    assertEquals("1HGCM82633A004352", client.getVin());
  }

  @Test
  @DisplayName("Get VIN converts non-success status to client exception")
  void getVin_whenServerReturnsError_throwsClientException() throws Exception {
    startServer(429, "too many requests");
    var client = new RandomVinClient(properties(serverUrl()));

    var exception = assertThrows(RandomVinClientException.class, client::getVin);

    assertEquals(429, exception.getStatusCode());
  }

  private void startServer(int status, String responseBody) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
  }

  private String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private VehicleProperties properties(String url) {
    var properties = new VehicleProperties();
    properties.getRandomVin().setUrl(url);
    properties.getRandomVin().setConnectTimeout(Duration.ofSeconds(1));
    properties.getRandomVin().setRequestTimeout(Duration.ofSeconds(1));
    return properties;
  }
}

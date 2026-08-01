package dev.christopherbell.vehicle.nhtsa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClient;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClientException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NhtsaVinClientTest {
  private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;
  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("Decode VINs posts encoded batch data and parses results")
  void decodeVins_whenServerReturnsResults_returnsParsedRows() throws Exception {
    var requestBody = new AtomicReference<String>();
    startServer(200, "{\"Results\":[{\"VIN\":\"1HGCM82633A004352\",\"Make\":\"HONDA\"}]}", requestBody);
    var client = new NhtsaVinClient(new ObjectMapper(), properties(serverUrl(), 5));

    var result = client.decodeVins(List.of(
        new NhtsaVinClient.NhtsaVinDecodeRequest("1HGCM82633A004352", 2003)));

    assertEquals(1, result.size());
    assertEquals("HONDA", result.get(0).get("Make"));
    assertTrue(requestBody.get().contains("format=json"));
    assertTrue(requestBody.get().contains("data=1HGCM82633A004352%2C2003"));
  }

  @Test
  @DisplayName("Decode VINs rejects empty batches")
  void decodeVins_whenBatchEmpty_throwsInvalidRequestException() throws Exception {
    var client = new NhtsaVinClient(new ObjectMapper(), properties("http://localhost:1", 5));

    assertThrows(InvalidRequestException.class, () -> client.decodeVins(List.of()));
  }

  @Test
  @DisplayName("Decode VINs rejects batches over configured maximum")
  void decodeVins_whenBatchTooLarge_throwsInvalidRequestException() throws Exception {
    var client = new NhtsaVinClient(new ObjectMapper(), properties("http://localhost:1", 1));

    assertThrows(InvalidRequestException.class, () -> client.decodeVins(List.of(
        new NhtsaVinClient.NhtsaVinDecodeRequest("VIN1", null),
        new NhtsaVinClient.NhtsaVinDecodeRequest("VIN2", null))));
  }

  @Test
  @DisplayName("Decode VINs converts non-success HTTP status to client exception")
  void decodeVins_whenServerReturnsError_throwsClientException() throws Exception {
    startServer(503, "unavailable", new AtomicReference<>());
    var client = new NhtsaVinClient(new ObjectMapper(), properties(serverUrl(), 5));

    var exception = assertThrows(
        NhtsaVinClientException.class,
        () -> client.decodeVins(List.of(new NhtsaVinClient.NhtsaVinDecodeRequest("VIN1", null))));

    assertEquals(503, exception.getStatusCode());
  }

  @Test
  @DisplayName("Decode VINs rejects responses with no results")
  void decodeVins_whenServerReturnsNoResults_throwsInvalidRequestException() throws Exception {
    startServer(200, "{\"Results\":[]}", new AtomicReference<>());
    var client = new NhtsaVinClient(new ObjectMapper(), properties(serverUrl(), 5));

    assertThrows(
        InvalidRequestException.class,
        () -> client.decodeVins(List.of(new NhtsaVinClient.NhtsaVinDecodeRequest("VIN1", null))));
  }

  @Test
  @DisplayName("Decode VINs rejects a response one byte above the response limit")
  void decodeVins_whenResponseExceedsLimit_throwsBodyLimitException() throws Exception {
    var json = "{\"Results\":[{\"VIN\":\"VIN1\"}]}";
    startServer(
        200,
        json + " ".repeat(MAXIMUM_RESPONSE_BYTES + 1 - json.length()),
        new AtomicReference<>());
    var client = new NhtsaVinClient(new ObjectMapper(), properties(serverUrl(), 5));

    assertThrows(
        dev.christopherbell.libs.http.BodyLimitExceededException.class,
        () -> client.decodeVins(List.of(new NhtsaVinClient.NhtsaVinDecodeRequest("VIN1", null))));
  }

  @Test
  @DisplayName("Decode VINs preserves malformed JSON diagnostics")
  void decodeVins_whenResponseIsMalformed_throwsParsingException() throws Exception {
    startServer(200, "not-json", new AtomicReference<>());
    var client = new NhtsaVinClient(new ObjectMapper(), properties(serverUrl(), 5));

    assertThrows(
        tools.jackson.core.exc.StreamReadException.class,
        () -> client.decodeVins(List.of(new NhtsaVinClient.NhtsaVinDecodeRequest("VIN1", null))));
  }

  @Test
  @DisplayName("Decode VINs times out when a response stalls after headers")
  void decodeVins_whenBodyStallsAfterHeaders_honorsRequestTimeout() throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var releaseBody = new CountDownLatch(1);
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, 128);
      exchange.getResponseBody().write('{');
      exchange.getResponseBody().flush();
      bodyStarted.countDown();
      try {
        releaseBody.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    var client = new NhtsaVinClient(
        new ObjectMapper(), properties(serverUrl(), 5, Duration.ofMillis(150)));

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var result = executor.submit(() -> client.decodeVins(List.of(
        new NhtsaVinClient.NhtsaVinDecodeRequest("VIN1", null))));
    try {
      assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));

      var exception = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
      assertTrue(exception.getCause() instanceof HttpTimeoutException);
    } finally {
      result.cancel(true);
      releaseBody.countDown();
      executor.close();
    }
  }

  private void startServer(int status, String responseBody, AtomicReference<String> requestBody)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

  private VehicleProperties properties(String url, int maxBatchSize) {
    return properties(url, maxBatchSize, Duration.ofSeconds(1));
  }

  private VehicleProperties properties(String url, int maxBatchSize, Duration requestTimeout) {
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setUrl(url);
    properties.getNhtsaVin().setConnectTimeout(Duration.ofSeconds(1));
    properties.getNhtsaVin().setRequestTimeout(requestTimeout);
    properties.getNhtsaVin().setMaxBatchSize(maxBatchSize);
    return properties;
  }
}

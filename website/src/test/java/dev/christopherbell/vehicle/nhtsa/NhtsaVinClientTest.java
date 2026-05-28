package dev.christopherbell.vehicle.nhtsa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClient;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClientException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NhtsaVinClientTest {
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
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setUrl(url);
    properties.getNhtsaVin().setConnectTimeout(Duration.ofSeconds(1));
    properties.getNhtsaVin().setRequestTimeout(Duration.ofSeconds(1));
    properties.getNhtsaVin().setMaxBatchSize(maxBatchSize);
    return properties;
  }
}

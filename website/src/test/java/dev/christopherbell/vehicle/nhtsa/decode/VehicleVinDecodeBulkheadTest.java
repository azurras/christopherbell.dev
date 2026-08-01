package dev.christopherbell.vehicle.nhtsa.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VehicleVinDecodeBulkheadTest {
  private static final String VIN = "1HGCM82633A004352";
  private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

  @Mock private VehicleVinDecodeCacheRepository cacheRepository;
  @Mock private NhtsaVinClient nhtsaVinClient;
  @Mock private VehicleVinDecodeRateLimiter rateLimiter;

  @Test
  void exhaustedBulkheadRejectsASingleMissWithoutStartingRemoteWork() throws Exception {
    var bulkhead = new VinDecodeBulkhead(1);
    try (var ignored = bulkhead.tryAcquire().orElseThrow()) {
      when(cacheRepository.findById(VIN)).thenReturn(Optional.empty());
      var service = service(properties(Duration.ofHours(1)), bulkhead);

      assertThrows(
          VehicleVinDecodeUnavailableException.class,
          () -> service.decode(new VehicleVinDecodeRequest(VIN), "client"));

      verifyNoInteractions(nhtsaVinClient);
    }
  }

  @Test
  void exhaustedBulkheadMarksBatchMissesUnavailableWithoutStartingRemoteWork() throws Exception {
    var bulkhead = new VinDecodeBulkhead(1);
    try (var ignored = bulkhead.tryAcquire().orElseThrow()) {
      when(cacheRepository.findById(VIN)).thenReturn(Optional.empty());
      var service = service(properties(Duration.ofHours(1)), bulkhead);

      var response = service.decodeBatch(
          new VehicleVinDecodeBatchRequest(List.of(VIN)), "client");

      assertEquals("UPSTREAM_UNAVAILABLE", response.results().get(0).status());
      verifyNoInteractions(nhtsaVinClient);
    }
  }

  @Test
  void upstreamExceptionReleasesPermitForTheNextRemoteCall() throws Exception {
    var bulkhead = new VinDecodeBulkhead(1);
    when(cacheRepository.findById(VIN)).thenReturn(Optional.empty());
    when(nhtsaVinClient.decodeVin(eq(VIN), eq(null)))
        .thenThrow(new IOException("upstream reset"))
        .thenReturn(Map.of("VIN", VIN, "Make", "HONDA"));
    var service = service(properties(Duration.ZERO), bulkhead);

    assertThrows(
        VehicleVinDecodeUnavailableException.class,
        () -> service.decode(new VehicleVinDecodeRequest(VIN), "client"));

    var response = service.decode(new VehicleVinDecodeRequest(VIN), "client");
    assertEquals("HONDA", response.make());
    org.mockito.Mockito.verify(cacheRepository).save(any(VehicleVinDecodeCache.class));
  }

  @Test
  void upstreamBodyTimeoutReleasesPermitForTheNextRemoteCall() throws Exception {
    var requestCount = new AtomicInteger();
    var bodyStarted = new CountDownLatch(1);
    var releaseFirstBody = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      if (requestCount.incrementAndGet() == 1) {
        exchange.sendResponseHeaders(200, 128);
        exchange.getResponseBody().write('{');
        exchange.getResponseBody().flush();
        bodyStarted.countDown();
        try {
          releaseFirstBody.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          exchange.close();
        }
        return;
      }
      var body = ("{\"Results\":[{\"VIN\":\"" + VIN + "\",\"Make\":\"HONDA\"}]}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    var properties = properties(Duration.ZERO);
    properties.getNhtsaVin().setUrl("http://localhost:" + server.getAddress().getPort());
    properties.getNhtsaVin().setConnectTimeout(Duration.ofSeconds(1));
    properties.getNhtsaVin().setRequestTimeout(Duration.ofMillis(150));
    properties.getNhtsaVin().setMaxBatchSize(20);
    when(cacheRepository.findById(VIN)).thenReturn(Optional.empty());
    var realClient = new NhtsaVinClient(new ObjectMapper(), properties);
    var service = new VehicleVinDecodeService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        realClient,
        properties,
        cacheRepository,
        rateLimiter,
        new VinDecodeBulkhead(1));

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var firstCall = executor.submit(
        () -> service.decode(new VehicleVinDecodeRequest(VIN), "client"));
    try {
      assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
      var exception = assertThrows(
          ExecutionException.class, () -> firstCall.get(1, TimeUnit.SECONDS));
      assertTrue(exception.getCause() instanceof VehicleVinDecodeUnavailableException);
    } finally {
      firstCall.cancel(true);
      releaseFirstBody.countDown();
      executor.close();
    }

    try {
      var response = service.decode(new VehicleVinDecodeRequest(VIN), "client");
      assertEquals("HONDA", response.make());
      assertEquals(2, requestCount.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void singleRemoteCallReleasesPermitBeforeWritingTheCache() throws Exception {
    var bulkhead = new VinDecodeBulkhead(1);
    when(cacheRepository.findById(VIN)).thenReturn(Optional.empty());
    when(nhtsaVinClient.decodeVin(eq(VIN), eq(null)))
        .thenReturn(Map.of("VIN", VIN, "Make", "HONDA"));
    when(cacheRepository.save(any(VehicleVinDecodeCache.class))).thenAnswer(invocation -> {
      assertPermitAvailable(bulkhead);
      return invocation.getArgument(0);
    });
    var service = service(properties(Duration.ZERO), bulkhead);

    var response = service.decode(new VehicleVinDecodeRequest(VIN), "client");

    assertEquals("HONDA", response.make());
  }

  @Test
  void batchRemoteCallReleasesPermitBeforeWritingTheCache() throws Exception {
    var bulkhead = new VinDecodeBulkhead(1);
    when(cacheRepository.findById(VIN)).thenReturn(Optional.empty());
    when(nhtsaVinClient.decodeVins(any()))
        .thenReturn(List.of(Map.of("VIN", VIN, "Make", "HONDA")));
    when(cacheRepository.save(any(VehicleVinDecodeCache.class))).thenAnswer(invocation -> {
      assertPermitAvailable(bulkhead);
      return invocation.getArgument(0);
    });
    var service = service(properties(Duration.ZERO), bulkhead);

    var response = service.decodeBatch(
        new VehicleVinDecodeBatchRequest(List.of(VIN)), "client");

    assertEquals("SUCCESS", response.results().get(0).status());
  }

  private void assertPermitAvailable(VinDecodeBulkhead bulkhead) {
    var permit = bulkhead.tryAcquire();
    assertTrue(permit.isPresent());
    permit.orElseThrow().close();
  }

  private VehicleVinDecodeService service(
      VehicleProperties properties,
      VinDecodeBulkhead bulkhead
  ) {
    return new VehicleVinDecodeService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        nhtsaVinClient,
        properties,
        cacheRepository,
        rateLimiter,
        bulkhead);
  }

  private VehicleProperties properties(Duration cooldown) {
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setCooldown(cooldown);
    properties.getVinDecoder().setCacheTtl(Duration.ofDays(30));
    properties.getVinDecoder().setDecoderVersion("vpic-test");
    properties.getVinDecoder().setMaxBatchSize(20);
    return properties;
  }
}

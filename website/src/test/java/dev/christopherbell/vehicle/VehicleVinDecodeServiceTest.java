package dev.christopherbell.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClient;
import dev.christopherbell.vehicle.nhtsa.decode.NhtsaVinClientException;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeCacheRepository;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeRateLimiter;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeService;
import dev.christopherbell.vehicle.nhtsa.decode.VehicleVinDecodeUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VehicleVinDecodeServiceTest {
  private static final String CLIENT_KEY = "ip:127.0.0.1";
  private static final Instant NOW = Instant.parse("2026-05-10T12:00:00Z");
  private static final String DECODER_VERSION = "vpic-2026-07";
  private static final String REMOTE_VIN = "2FMDK4JCXEBB62196";
  private static final String MISSING_VIN = "1M8GDM9AXKP042788";

  @Mock private VehicleVinDecodeCacheRepository cacheRepository;
  @Mock private NhtsaVinClient nhtsaVinClient;
  @Mock private VehicleVinDecodeRateLimiter rateLimiter;

  @Test
  @DisplayName("Decodes a VIN and maps summary fields")
  void testDecode_whenValidVin_returnsSummaryAndRawValues() throws Exception {
    var service = service();
    var raw = Map.of(
        "VIN", VehicleStub.VIN,
        "Make", "HONDA",
        "Model", "Accord",
        "ModelYear", "2003",
        "BodyClass", "Coupe",
        "PlantCity", "MARYSVILLE",
        "PlantState", "OHIO",
        "PlantCountry", "UNITED STATES (USA)",
        "ErrorCode", "0",
        "ErrorText", "0 - VIN decoded clean."
    );
    when(cacheRepository.findById(eq(VehicleStub.VIN))).thenReturn(Optional.empty());
    when(nhtsaVinClient.decodeVin(eq(VehicleStub.VIN), eq(null))).thenReturn(raw);

    var result = service.decode(
        new VehicleVinDecodeRequest(" " + VehicleStub.VIN.toLowerCase() + " "),
        CLIENT_KEY);

    assertEquals(VehicleStub.VIN, result.vin());
    assertEquals("HONDA", result.make());
    assertEquals("Accord", result.model());
    assertEquals(2003, result.year());
    assertEquals("Coupe", result.body());
    assertEquals("MARYSVILLE", result.plantCity());
    assertEquals("OHIO", result.plantState());
    assertEquals("UNITED STATES (USA)", result.plantCountry());
    assertEquals(raw, result.rawDecodedValues());
    verify(rateLimiter).check(eq(CLIENT_KEY));
    verify(nhtsaVinClient).decodeVin(eq(VehicleStub.VIN), eq(null));
    verify(cacheRepository).save(any(VehicleVinDecodeCache.class));
  }

  @Test
  @DisplayName("Returns cached VIN decode without calling NHTSA")
  void testDecode_whenCached_returnsCacheHit() throws Exception {
    var service = service();
    var cachedResponse = VehicleVinDecodeResponse.builder()
        .vin(VehicleStub.VIN)
        .make(VehicleStub.MAKE)
        .model(VehicleStub.MODEL)
        .year(VehicleStub.YEAR)
        .rawDecodedValues(Map.of("VIN", VehicleStub.VIN))
        .build();
    when(cacheRepository.findById(eq(VehicleStub.VIN)))
        .thenReturn(Optional.of(VehicleVinDecodeCache.builder()
            .vin(VehicleStub.VIN)
            .response(cachedResponse)
            .decoderVersion(DECODER_VERSION)
            .refreshedOn(NOW.minus(Duration.ofMinutes(5)))
            .expiresOn(NOW.plus(Duration.ofHours(1)))
            .createdOn(NOW)
            .lastUpdatedOn(NOW)
            .build()));

    var result = service.decode(new VehicleVinDecodeRequest(VehicleStub.VIN), CLIENT_KEY);

    assertEquals(cachedResponse, result);
    verify(rateLimiter).check(eq(CLIENT_KEY));
    verifyNoInteractions(nhtsaVinClient);
  }

  @Test
  @DisplayName("Refreshes an expired VIN decode cache entry")
  void testDecode_whenCacheExpired_refreshesFromNhtsa() throws Exception {
    var service = service();
    var staleResponse = VehicleVinDecodeResponse.builder().vin(VehicleStub.VIN).make("OLD").build();
    when(cacheRepository.findById(eq(VehicleStub.VIN)))
        .thenReturn(Optional.of(cache(staleResponse, DECODER_VERSION, NOW.minusSeconds(1))))
        .thenReturn(Optional.of(cache(staleResponse, DECODER_VERSION, NOW.minusSeconds(1))));
    when(nhtsaVinClient.decodeVin(eq(VehicleStub.VIN), eq(null)))
        .thenReturn(Map.of("VIN", VehicleStub.VIN, "Make", VehicleStub.MAKE));

    var result = service.decode(new VehicleVinDecodeRequest(VehicleStub.VIN), CLIENT_KEY);

    assertEquals(VehicleStub.MAKE, result.make());
    verify(nhtsaVinClient).decodeVin(eq(VehicleStub.VIN), eq(null));
    verify(cacheRepository).save(any(VehicleVinDecodeCache.class));
  }

  @Test
  @DisplayName("Refreshes a VIN decode cache entry from an older decoder version")
  void testDecode_whenCacheVersionIsOld_refreshesFromNhtsa() throws Exception {
    var service = service();
    var staleResponse = VehicleVinDecodeResponse.builder().vin(VehicleStub.VIN).make("OLD").build();
    when(cacheRepository.findById(eq(VehicleStub.VIN)))
        .thenReturn(Optional.of(cache(staleResponse, "vpic-old", NOW.plus(Duration.ofHours(1)))))
        .thenReturn(Optional.of(cache(staleResponse, "vpic-old", NOW.plus(Duration.ofHours(1)))));
    when(nhtsaVinClient.decodeVin(eq(VehicleStub.VIN), eq(null)))
        .thenReturn(Map.of("VIN", VehicleStub.VIN, "Make", VehicleStub.MAKE));

    var result = service.decode(new VehicleVinDecodeRequest(VehicleStub.VIN), CLIENT_KEY);

    assertEquals(VehicleStub.MAKE, result.make());
    verify(nhtsaVinClient).decodeVin(eq(VehicleStub.VIN), eq(null));
  }

  @Test
  @DisplayName("Failed refresh leaves the stale cache entry unchanged")
  void testDecode_whenRefreshFails_doesNotExtendStaleCache() throws Exception {
    var service = service();
    var stale = cache(
        VehicleVinDecodeResponse.builder().vin(VehicleStub.VIN).make("OLD").build(),
        DECODER_VERSION,
        NOW.minusSeconds(1));
    when(cacheRepository.findById(eq(VehicleStub.VIN)))
        .thenReturn(Optional.of(stale))
        .thenReturn(Optional.of(stale));
    when(nhtsaVinClient.decodeVin(eq(VehicleStub.VIN), eq(null)))
        .thenThrow(new NhtsaVinClientException(503));

    assertThrows(VehicleVinDecodeUnavailableException.class,
        () -> service.decode(new VehicleVinDecodeRequest(VehicleStub.VIN), CLIENT_KEY));

    verify(cacheRepository, never()).save(any(VehicleVinDecodeCache.class));
  }

  @Test
  @DisplayName("Cools down after NHTSA rate limits VIN decode")
  void testDecode_whenNhtsaRateLimits_coolsDown() throws Exception {
    var service = service();
    when(cacheRepository.findById(eq(VehicleStub.VIN))).thenReturn(Optional.empty());
    when(nhtsaVinClient.decodeVin(eq(VehicleStub.VIN), eq(null)))
        .thenThrow(new NhtsaVinClientException(429));

    assertThrows(VehicleVinDecodeUnavailableException.class,
        () -> service.decode(new VehicleVinDecodeRequest(VehicleStub.VIN), CLIENT_KEY));
    assertThrows(VehicleVinDecodeUnavailableException.class,
        () -> service.decode(new VehicleVinDecodeRequest(VehicleStub.VIN), CLIENT_KEY));

    verify(nhtsaVinClient).decodeVin(eq(VehicleStub.VIN), eq(null));
  }

  @Test
  @DisplayName("Rejects invalid VIN input before calling NHTSA")
  void testDecode_whenInvalidVin_throwsInvalidRequest() {
    var service = service();

    assertThrows(InvalidRequestException.class,
        () -> service.decode(new VehicleVinDecodeRequest("bad"), CLIENT_KEY));
    verifyNoInteractions(rateLimiter, cacheRepository, nhtsaVinClient);
  }

  @Test
  @DisplayName("Batch decode preserves ordered cached, invalid, remote, and missing results")
  void testDecodeBatch_whenMixedResults_preservesOrderAndPartialErrors() throws Exception {
    var service = service();
    var cachedResponse = VehicleVinDecodeResponse.builder()
        .vin(VehicleStub.VIN)
        .make("CACHED")
        .build();
    when(cacheRepository.findById(anyString())).thenAnswer(invocation -> {
      var vin = invocation.getArgument(0, String.class);
      return VehicleStub.VIN.equals(vin)
          ? Optional.of(cache(cachedResponse, DECODER_VERSION, NOW.plusSeconds(60)))
          : Optional.empty();
    });
    when(nhtsaVinClient.decodeVins(anyList())).thenReturn(List.of(
        Map.of("VIN", REMOTE_VIN, "Make", "FORD")
    ));

    var submitted = java.util.Arrays.asList(
        VehicleStub.VIN,
        "bad",
        null,
        REMOTE_VIN.toLowerCase(),
        MISSING_VIN
    );
    var result = service.decodeBatch(new VehicleVinDecodeBatchRequest(submitted), CLIENT_KEY);

    assertEquals(5, result.submittedCount());
    assertEquals(2, result.successCount());
    assertEquals(3, result.errorCount());
    assertEquals(submitted,
        result.results().stream().map(entry -> entry.submittedVin()).toList());
    assertEquals(List.of("SUCCESS", "INVALID_VIN", "INVALID_VIN", "SUCCESS", "UPSTREAM_NO_RESULT"),
        result.results().stream().map(entry -> entry.status()).toList());
    assertEquals("CACHED", result.results().get(0).decoded().make());
    assertEquals("FORD", result.results().get(3).decoded().make());
    verify(rateLimiter).check(CLIENT_KEY, 5);
    verify(nhtsaVinClient).decodeVins(anyList());
  }

  @Test
  @DisplayName("Batch decode rejects an envelope above the configured maximum")
  void testDecodeBatch_whenAboveMaximum_rejectsBeforeEffects() {
    var properties = properties();
    properties.getVinDecoder().setMaxBatchSize(2);
    var service = service(properties);

    assertThrows(InvalidRequestException.class, () -> service.decodeBatch(
        new VehicleVinDecodeBatchRequest(List.of(VehicleStub.VIN, REMOTE_VIN, MISSING_VIN)),
        CLIENT_KEY));

    verifyNoInteractions(rateLimiter, cacheRepository, nhtsaVinClient);
  }

  @Test
  @DisplayName("Batch decode reports a per-VIN cache error without losing the response envelope")
  void testDecodeBatch_whenCacheReadFails_returnsSpecificEntryError() throws Exception {
    var service = service();
    when(cacheRepository.findById(VehicleStub.VIN))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("mongo unavailable"));

    var result = service.decodeBatch(
        new VehicleVinDecodeBatchRequest(List.of(VehicleStub.VIN)), CLIENT_KEY);

    assertEquals(1, result.errorCount());
    assertEquals("CACHE_UNAVAILABLE", result.results().get(0).status());
    verifyNoInteractions(nhtsaVinClient);
  }

  private VehicleVinDecodeService service() {
    return service(properties());
  }

  private VehicleProperties properties() {
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setCooldown(Duration.ofHours(24));
    properties.getVinDecoder().setCacheTtl(Duration.ofDays(30));
    properties.getVinDecoder().setDecoderVersion(DECODER_VERSION);
    properties.getVinDecoder().setMaxBatchSize(20);
    return properties;
  }

  private VehicleVinDecodeService service(VehicleProperties properties) {
    return new VehicleVinDecodeService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        nhtsaVinClient,
        properties,
        cacheRepository,
        rateLimiter);
  }

  private VehicleVinDecodeCache cache(
      VehicleVinDecodeResponse response,
      String decoderVersion,
      Instant expiresOn
  ) {
    return VehicleVinDecodeCache.builder()
        .vin(VehicleStub.VIN)
        .response(response)
        .decoderVersion(decoderVersion)
        .refreshedOn(NOW.minus(Duration.ofDays(1)))
        .expiresOn(expiresOn)
        .createdOn(NOW.minus(Duration.ofDays(1)))
        .lastUpdatedOn(NOW.minus(Duration.ofDays(1)))
        .build();
  }
}

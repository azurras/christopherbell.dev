package dev.christopherbell.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import dev.christopherbell.vehicle.nhtsa.NhtsaVinClient;
import dev.christopherbell.vehicle.nhtsa.NhtsaVinClientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
            .createdOn(NOW)
            .lastUpdatedOn(NOW)
            .build()));

    var result = service.decode(new VehicleVinDecodeRequest(VehicleStub.VIN), CLIENT_KEY);

    assertEquals(cachedResponse, result);
    verify(rateLimiter).check(eq(CLIENT_KEY));
    verifyNoInteractions(nhtsaVinClient);
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

  private VehicleVinDecodeService service() {
    var properties = new VehicleProperties();
    properties.getNhtsaVin().setCooldown(Duration.ofHours(24));
    return new VehicleVinDecodeService(
        Clock.fixed(NOW, ZoneOffset.UTC),
        nhtsaVinClient,
        properties,
        cacheRepository,
        rateLimiter);
  }
}

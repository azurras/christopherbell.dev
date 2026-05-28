package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VehicleVinDecodeService {
  private static final Pattern VIN_PATTERN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");
  private static final String TEMPORARILY_UNAVAILABLE =
      "VIN decoding is temporarily unavailable. Please try again later.";

  private final Clock clock;
  private final VehicleVinDecodeCacheRepository cacheRepository;
  private final NhtsaVinClient nhtsaVinClient;
  private final VehicleProperties.NhtsaVin nhtsaProperties;
  private final VehicleVinDecodeRateLimiter rateLimiter;
  private final Map<String, Object> vinLocks = new ConcurrentHashMap<>();

  private volatile Instant nhtsaUnavailableUntil;

  public VehicleVinDecodeService(
      Clock clock,
      NhtsaVinClient nhtsaVinClient,
      VehicleProperties vehicleProperties,
      VehicleVinDecodeCacheRepository cacheRepository,
      VehicleVinDecodeRateLimiter rateLimiter
  ) {
    this.clock = clock;
    this.cacheRepository = cacheRepository;
    this.nhtsaVinClient = nhtsaVinClient;
    this.nhtsaProperties = vehicleProperties.getNhtsaVin();
    this.rateLimiter = rateLimiter;
  }

  public VehicleVinDecodeResponse decode(VehicleVinDecodeRequest request, String clientKey)
      throws InvalidRequestException {
    if (request == null) {
      throw new InvalidRequestException("VIN decode request cannot be null.");
    }

    var vin = normalizeVin(request.vin());
    rateLimiter.check(rateLimitKey(clientKey));

    var cachedResponse = cachedResponse(vin);
    if (cachedResponse != null) {
      return cachedResponse;
    }
    if (isNhtsaCoolingDown()) {
      throw temporarilyUnavailable();
    }

    var lock = vinLocks.computeIfAbsent(vin, ignored -> new Object());
    try {
      synchronized (lock) {
        cachedResponse = cachedResponse(vin);
        if (cachedResponse != null) {
          return cachedResponse;
        }
        if (isNhtsaCoolingDown()) {
          throw temporarilyUnavailable();
        }
        return decodeAndCache(vin);
      }
    } finally {
      vinLocks.remove(vin, lock);
    }
  }

  private VehicleVinDecodeResponse decodeAndCache(String vin) {
    try {
      var response = toResponse(vin, nhtsaVinClient.decodeVin(vin, null));
      saveCachedResponse(vin, response);
      return response;
    } catch (NhtsaVinClientException e) {
      coolDownNhtsa("NHTSA VIN decode failed with HTTP status " + e.getStatusCode(), e);
      throw temporarilyUnavailable(e);
    } catch (InvalidRequestException e) {
      throw new VehicleVinDecodeUnavailableException(TEMPORARILY_UNAVAILABLE, e);
    } catch (IOException e) {
      coolDownNhtsa("NHTSA VIN decode failed while fetching VIN details", e);
      throw temporarilyUnavailable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      coolDownNhtsa("NHTSA VIN decode was interrupted", e);
      throw temporarilyUnavailable(e);
    }
  }

  private void coolDownNhtsa(String reason, Throwable cause) {
    nhtsaUnavailableUntil = Instant.now(clock).plus(nhtsaProperties.getCooldown());
    log.warn("{}. Cooling down until {}.", reason, nhtsaUnavailableUntil, cause);
  }

  private VehicleVinDecodeResponse cachedResponse(String vin) {
    try {
      return cacheRepository.findById(vin)
          .map(VehicleVinDecodeCache::getResponse)
          .orElse(null);
    } catch (DataAccessException e) {
      throw temporarilyUnavailable(e);
    }
  }

  private void saveCachedResponse(String vin, VehicleVinDecodeResponse response) {
    try {
      var now = Instant.now(clock);
      cacheRepository.save(VehicleVinDecodeCache.builder()
          .vin(vin)
          .response(response)
          .createdOn(now)
          .lastUpdatedOn(now)
          .build());
    } catch (DataAccessException e) {
      log.warn("Unable to cache VIN decode response for {}.", vin, e);
    }
  }

  private boolean isNhtsaCoolingDown() {
    return nhtsaUnavailableUntil != null && nhtsaUnavailableUntil.isAfter(Instant.now(clock));
  }

  private VehicleVinDecodeUnavailableException temporarilyUnavailable() {
    return new VehicleVinDecodeUnavailableException(TEMPORARILY_UNAVAILABLE);
  }

  private VehicleVinDecodeUnavailableException temporarilyUnavailable(Throwable cause) {
    return new VehicleVinDecodeUnavailableException(TEMPORARILY_UNAVAILABLE, cause);
  }

  private String rateLimitKey(String clientKey) {
    return clientKey == null || clientKey.isBlank() ? "anonymous" : clientKey;
  }

  private VehicleVinDecodeResponse toResponse(String vin, Map<String, String> values) {
    return VehicleVinDecodeResponse.builder()
        .vin(vin)
        .make(value(values, "Make"))
        .model(value(values, "Model"))
        .year(toInteger(value(values, "ModelYear")))
        .body(value(values, "BodyClass"))
        .plantCity(value(values, "PlantCity"))
        .plantState(value(values, "PlantState"))
        .plantCountry(value(values, "PlantCountry"))
        .errorCode(value(values, "ErrorCode"))
        .errorText(value(values, "ErrorText"))
        .rawDecodedValues(values)
        .build();
  }

  private String normalizeVin(String rawVin) throws InvalidRequestException {
    if (rawVin == null || rawVin.isBlank()) {
      throw new InvalidRequestException("VIN cannot be null or blank.");
    }

    var vin = rawVin.trim().toUpperCase();
    if (!VIN_PATTERN.matcher(vin).matches()) {
      throw new InvalidRequestException("VIN must be 17 valid VIN characters.");
    }
    return vin;
  }

  private String value(Map<String, String> values, String key) {
    return values == null ? null : values.get(key);
  }

  private Integer toInteger(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchEntry;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchResponse;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final VehicleProperties.VinDecoder vinDecoderProperties;
  private final VinDecodeBulkhead bulkhead;
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
    this(
        clock,
        nhtsaVinClient,
        vehicleProperties,
        cacheRepository,
        rateLimiter,
        new VinDecodeBulkhead());
  }

  @Autowired
  VehicleVinDecodeService(
      Clock clock,
      NhtsaVinClient nhtsaVinClient,
      VehicleProperties vehicleProperties,
      VehicleVinDecodeCacheRepository cacheRepository,
      VehicleVinDecodeRateLimiter rateLimiter,
      VinDecodeBulkhead bulkhead
  ) {
    this.clock = clock;
    this.cacheRepository = cacheRepository;
    this.nhtsaVinClient = nhtsaVinClient;
    this.nhtsaProperties = vehicleProperties.getNhtsaVin();
    this.vinDecoderProperties = vehicleProperties.getVinDecoder();
    this.bulkhead = bulkhead;
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
    final Map<String, String> values;
    try (var ignored = bulkhead.tryAcquire().orElseThrow(this::temporarilyUnavailable)) {
      values = nhtsaVinClient.decodeVin(vin, null);
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
    var response = toResponse(vin, values);
    saveCachedResponse(vin, response);
    return response;
  }

  private void coolDownNhtsa(String reason, Throwable cause) {
    nhtsaUnavailableUntil = Instant.now(clock).plus(nhtsaProperties.getCooldown());
    log.warn("{}. Cooling down until {}.", reason, nhtsaUnavailableUntil, cause);
  }

  private VehicleVinDecodeResponse cachedResponse(String vin) {
    try {
      return cacheRepository.findById(vin)
          .filter(cached -> cached.isFresh(
              vinDecoderProperties.getDecoderVersion(), Instant.now(clock)))
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
          .decoderVersion(vinDecoderProperties.getDecoderVersion())
          .refreshedOn(now)
          .expiresOn(now.plus(vinDecoderProperties.getCacheTtl()))
          .createdOn(now)
          .lastUpdatedOn(now)
          .build());
    } catch (DataAccessException e) {
      log.warn("Unable to cache VIN decode response for {}.", vin, e);
    }
  }

  public VehicleVinDecodeBatchResponse decodeBatch(
      VehicleVinDecodeBatchRequest request, String clientKey) throws InvalidRequestException {
    validateBatchEnvelope(request);
    rateLimiter.check(rateLimitKey(clientKey), request.vins().size());

    var normalizedByIndex = new ArrayList<String>(request.vins().size());
    var decodedByVin = new LinkedHashMap<String, VehicleVinDecodeResponse>();
    var misses = new LinkedHashMap<String, NhtsaVinClient.NhtsaVinDecodeRequest>();
    var cacheUnavailableVins = new HashSet<String>();
    for (var submittedVin : request.vins()) {
      try {
        var normalizedVin = normalizeVin(submittedVin);
        normalizedByIndex.add(normalizedVin);
        final VehicleVinDecodeResponse cached;
        try {
          cached = cachedResponse(normalizedVin);
        } catch (VehicleVinDecodeUnavailableException failure) {
          cacheUnavailableVins.add(normalizedVin);
          continue;
        }
        if (cached != null) {
          decodedByVin.put(normalizedVin, cached);
        } else {
          misses.putIfAbsent(
              normalizedVin, new NhtsaVinClient.NhtsaVinDecodeRequest(normalizedVin, null));
        }
      } catch (InvalidRequestException ignored) {
        normalizedByIndex.add(null);
      }
    }

    var unavailable = false;
    if (!misses.isEmpty()) {
      if (isNhtsaCoolingDown()) {
        unavailable = true;
      } else {
        var permit = bulkhead.tryAcquire();
        if (permit.isEmpty()) {
          unavailable = true;
        } else {
          List<Map<String, String>> remoteValues = List.of();
          try (var ignored = permit.orElseThrow()) {
            remoteValues = nhtsaVinClient.decodeVins(List.copyOf(misses.values()));
          } catch (NhtsaVinClientException | InvalidRequestException | IOException e) {
            coolDownNhtsa("NHTSA VIN batch decode failed", e);
            unavailable = true;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            coolDownNhtsa("NHTSA VIN batch decode was interrupted", e);
            unavailable = true;
          }
          for (var values : remoteValues) {
            var vin = normalizeNhtsaVin(values);
            if (vin != null && misses.containsKey(vin)) {
              var response = toResponse(vin, values);
              decodedByVin.put(vin, response);
              saveCachedResponse(vin, response);
            }
          }
        }
      }
    }

    var results = new ArrayList<VehicleVinDecodeBatchEntry>(request.vins().size());
    for (var index = 0; index < request.vins().size(); index++) {
      var submittedVin = request.vins().get(index);
      var normalizedVin = normalizedByIndex.get(index);
      if (normalizedVin == null) {
        results.add(VehicleVinDecodeBatchEntry.error(
            index, submittedVin, null, "INVALID_VIN", "VIN must be 17 valid VIN characters."));
      } else if (decodedByVin.containsKey(normalizedVin)) {
        results.add(VehicleVinDecodeBatchEntry.success(
            index, submittedVin, normalizedVin, decodedByVin.get(normalizedVin)));
      } else if (cacheUnavailableVins.contains(normalizedVin)) {
        results.add(VehicleVinDecodeBatchEntry.error(
            index,
            submittedVin,
            normalizedVin,
            "CACHE_UNAVAILABLE",
            "VIN cache is temporarily unavailable."));
      } else if (unavailable) {
        results.add(VehicleVinDecodeBatchEntry.error(
            index,
            submittedVin,
            normalizedVin,
            "UPSTREAM_UNAVAILABLE",
            TEMPORARILY_UNAVAILABLE));
      } else {
        results.add(VehicleVinDecodeBatchEntry.error(
            index,
            submittedVin,
            normalizedVin,
            "UPSTREAM_NO_RESULT",
            "NHTSA returned no result for this VIN."));
      }
    }
    return VehicleVinDecodeBatchResponse.from(results);
  }

  private void validateBatchEnvelope(VehicleVinDecodeBatchRequest request)
      throws InvalidRequestException {
    if (request == null || request.vins() == null || request.vins().isEmpty()) {
      throw new InvalidRequestException("VIN decode batch must contain at least one VIN.");
    }
    if (request.vins().size() > vinDecoderProperties.getMaxBatchSize()) {
      throw new InvalidRequestException("VIN decode batch cannot contain more than "
          + vinDecoderProperties.getMaxBatchSize() + " VINs.");
    }
  }

  private String normalizeNhtsaVin(Map<String, String> values) {
    var vin = value(values, "VIN");
    if (vin == null || vin.isBlank()) {
      return null;
    }
    return vin.trim().toUpperCase();
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

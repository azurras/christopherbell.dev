package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleProperties;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchEntry;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchFailure;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeBatchResponse;
import dev.christopherbell.vehicle.model.VehicleVinDecodeCache;
import dev.christopherbell.vehicle.model.VehicleVinDecodeRequest;
import dev.christopherbell.vehicle.model.VehicleVinDecodeResponse;
import dev.christopherbell.vehicle.model.VehicleVins;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VehicleVinDecodeService {
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
    } catch (NhtsaVinClientException httpFailure) {
      coolDownNhtsa(
          "NHTSA VIN decode failed with HTTP status " + httpFailure.getStatusCode(), httpFailure);
      throw temporarilyUnavailable(httpFailure);
    } catch (InvalidRequestException emptyResult) {
      throw temporarilyUnavailable(emptyResult);
    } catch (IOException fetchFailure) {
      coolDownNhtsa("NHTSA VIN decode failed while fetching VIN details", fetchFailure);
      throw temporarilyUnavailable(fetchFailure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      coolDownNhtsa("NHTSA VIN decode was interrupted", interrupted);
      throw temporarilyUnavailable(interrupted);
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
    } catch (DataAccessException cacheFailure) {
      throw temporarilyUnavailable(cacheFailure);
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
    } catch (DataAccessException cacheFailure) {
      log.warn("Unable to cache VIN decode response for {}.", vin, cacheFailure);
    }
  }

  public VehicleVinDecodeBatchResponse decodeBatch(
      VehicleVinDecodeBatchRequest request, String clientKey) throws InvalidRequestException {
    validateBatchEnvelope(request);
    rateLimiter.check(rateLimitKey(clientKey), request.vins().size());
    var lookup = lookUpCached(request.vins());
    var upstreamUnavailable = !lookup.misses().isEmpty() && !decodeMisses(lookup);
    var results = new ArrayList<VehicleVinDecodeBatchEntry>(request.vins().size());
    for (var index = 0; index < request.vins().size(); index++) {
      results.add(entryFor(index, request.vins().get(index), lookup, upstreamUnavailable));
    }
    return VehicleVinDecodeBatchResponse.from(results);
  }

  /** Normalizes each submitted VIN and splits the valid ones into cache hits and misses. */
  private BatchLookup lookUpCached(List<String> submittedVins) {
    var lookup = new BatchLookup(
        new ArrayList<>(submittedVins.size()),
        new LinkedHashMap<>(),
        new LinkedHashMap<>(),
        new HashSet<>());
    for (var submittedVin : submittedVins) {
      final String normalizedVin;
      try {
        normalizedVin = normalizeVin(submittedVin);
      } catch (InvalidRequestException invalidVin) {
        lookup.normalizedByIndex().add(null);
        continue;
      }
      lookup.normalizedByIndex().add(normalizedVin);
      final VehicleVinDecodeResponse cached;
      try {
        cached = cachedResponse(normalizedVin);
      } catch (VehicleVinDecodeUnavailableException cacheUnavailable) {
        lookup.cacheUnavailableVins().add(normalizedVin);
        continue;
      }
      if (cached != null) {
        lookup.decodedByVin().put(normalizedVin, cached);
      } else {
        lookup.misses().putIfAbsent(
            normalizedVin, new NhtsaVinClient.NhtsaVinDecodeRequest(normalizedVin, null));
      }
    }
    return lookup;
  }

  /**
   * Decodes the cache misses in one upstream call and caches each result.
   *
   * @return false when the upstream was cooling down, saturated or failed
   */
  private boolean decodeMisses(BatchLookup lookup) {
    if (isNhtsaCoolingDown()) {
      return false;
    }
    var permit = bulkhead.tryAcquire();
    if (permit.isEmpty()) {
      return false;
    }
    var upstreamAvailable = true;
    List<Map<String, String>> remoteValues = List.of();
    try (var ignored = permit.orElseThrow()) {
      remoteValues = nhtsaVinClient.decodeVins(List.copyOf(lookup.misses().values()));
    } catch (NhtsaVinClientException | InvalidRequestException | IOException upstreamFailure) {
      coolDownNhtsa("NHTSA VIN batch decode failed", upstreamFailure);
      upstreamAvailable = false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      coolDownNhtsa("NHTSA VIN batch decode was interrupted", interrupted);
      upstreamAvailable = false;
    }
    for (var values : remoteValues) {
      var vin = normalizeNhtsaVin(values);
      if (vin != null && lookup.misses().containsKey(vin)) {
        var response = toResponse(vin, values);
        lookup.decodedByVin().put(vin, response);
        saveCachedResponse(vin, response);
      }
    }
    return upstreamAvailable;
  }

  private static VehicleVinDecodeBatchEntry entryFor(
      int index, String submittedVin, BatchLookup lookup, boolean upstreamUnavailable) {
    var normalizedVin = lookup.normalizedByIndex().get(index);
    if (normalizedVin == null) {
      return VehicleVinDecodeBatchEntry.error(
          index, submittedVin, null, VehicleVinDecodeBatchFailure.INVALID_VIN);
    }
    if (lookup.decodedByVin().containsKey(normalizedVin)) {
      return VehicleVinDecodeBatchEntry.success(
          index, submittedVin, normalizedVin, lookup.decodedByVin().get(normalizedVin));
    }
    if (lookup.cacheUnavailableVins().contains(normalizedVin)) {
      return VehicleVinDecodeBatchEntry.error(
          index, submittedVin, normalizedVin, VehicleVinDecodeBatchFailure.CACHE_UNAVAILABLE);
    }
    if (upstreamUnavailable) {
      return VehicleVinDecodeBatchEntry.error(
          index, submittedVin, normalizedVin, VehicleVinDecodeBatchFailure.UPSTREAM_UNAVAILABLE);
    }
    return VehicleVinDecodeBatchEntry.error(
        index, submittedVin, normalizedVin, VehicleVinDecodeBatchFailure.UPSTREAM_NO_RESULT);
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
    return vin == null || vin.isBlank() ? null : VehicleVins.normalize(vin);
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

    var vin = VehicleVins.normalize(rawVin);
    if (!VehicleVins.isValid(vin)) {
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
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }

  /**
   * Working state for one batch decode: the normalized VIN at each submitted position (null when
   * invalid), the decoded results so far, the cache misses still to decode, and the VINs whose
   * cache read failed.
   */
  private record BatchLookup(
      List<String> normalizedByIndex,
      Map<String, VehicleVinDecodeResponse> decodedByVin,
      Map<String, NhtsaVinClient.NhtsaVinDecodeRequest> misses,
      Set<String> cacheUnavailableVins) {}
}

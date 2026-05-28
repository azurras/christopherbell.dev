package dev.christopherbell.vehicle.nhtsa.decode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.vehicle.model.VehicleProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Client for NHTSA vPIC VIN decoding.
 */
@Component
public class NhtsaVinClient {
  private static final TypeReference<NhtsaResponse> RESPONSE_TYPE = new TypeReference<>() {};

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final VehicleProperties.NhtsaVin properties;

  /**
   * Creates an NHTSA VIN client for the configured vPIC batch endpoint.
   *
   * @param objectMapper the mapper used to parse NHTSA JSON responses
   * @param vehicleProperties vehicle data collection configuration
   */
  public NhtsaVinClient(
      ObjectMapper objectMapper,
      VehicleProperties vehicleProperties
  ) {
    this.properties = vehicleProperties.getNhtsaVin();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .build();
    this.objectMapper = objectMapper;
    this.baseUrl = stripTrailingSlash(properties.getUrl());
  }

  /**
   * Decodes one VIN through the NHTSA batch endpoint.
   *
   * @param vin the VIN to decode
   * @param modelYear the optional model year to include in the decode request
   * @return the decoded values returned by NHTSA
   * @throws IOException when the HTTP call or response parsing fails
   * @throws InterruptedException when the HTTP call is interrupted
   * @throws InvalidRequestException when the request or response is invalid
   * @throws NhtsaVinClientException when NHTSA returns a non-success HTTP status
   */
  public Map<String, String> decodeVin(String vin, Integer modelYear)
      throws IOException, InterruptedException, InvalidRequestException, NhtsaVinClientException {
    return decodeVins(List.of(new NhtsaVinDecodeRequest(vin, modelYear))).get(0);
  }

  /**
   * Decodes a batch of VINs through the NHTSA vPIC batch endpoint.
   *
   * @param vins the VINs and optional model years to decode
   * @return decoded values returned by NHTSA
   * @throws IOException when the HTTP call or response parsing fails
   * @throws InterruptedException when the HTTP call is interrupted
   * @throws InvalidRequestException when the request or response is invalid
   * @throws NhtsaVinClientException when NHTSA returns a non-success HTTP status
   */
  public List<Map<String, String>> decodeVins(List<NhtsaVinDecodeRequest> vins)
      throws IOException, InterruptedException, InvalidRequestException, NhtsaVinClientException {
    if (vins == null || vins.isEmpty()) {
      throw new InvalidRequestException("VIN batch cannot be empty.");
    }
    if (vins.size() > properties.getMaxBatchSize()) {
      throw new InvalidRequestException(
          String.format("VIN batch cannot contain more than %s VINs.", properties.getMaxBatchSize()));
    }

    var body = "format=json&data=" + URLEncoder.encode(batchData(vins), StandardCharsets.UTF_8);
    var request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(properties.getRequestTimeout())
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new NhtsaVinClientException(response.statusCode());
    }

    var nhtsaResponse = objectMapper.readValue(response.body(), RESPONSE_TYPE);
    if (nhtsaResponse.Results() == null || nhtsaResponse.Results().isEmpty()) {
      throw new InvalidRequestException("NHTSA returned no VIN decode results.");
    }

    return nhtsaResponse.Results();
  }

  /**
   * Formats NHTSA batch request data.
   *
   * @param vins the VIN entries to format
   * @return the semicolon-delimited batch request data
   * @throws InvalidRequestException when the batch is invalid
   */
  private String batchData(List<NhtsaVinDecodeRequest> vins) throws InvalidRequestException {
    return vins.stream()
        .map(this::batchEntry)
        .collect(Collectors.joining(";"));
  }

  /**
   * Formats a single NHTSA batch request entry.
   *
   * @param request the VIN decode request entry
   * @return the formatted batch entry
   */
  private String batchEntry(NhtsaVinDecodeRequest request) {
    if (request.vin() == null || request.vin().isBlank()) {
      throw new IllegalArgumentException("VIN cannot be null or blank.");
    }
    var entry = request.vin().trim();
    if (request.modelYear() != null) {
      entry += "," + request.modelYear();
    }
    return entry;
  }

  /**
   * Removes a trailing slash from a URL.
   *
   * @param value the URL value to normalize
   * @return the URL without a trailing slash
   */
  private String stripTrailingSlash(String value) {
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  /**
   * A single NHTSA VIN decode request entry.
   *
   * @param vin the VIN to decode
   * @param modelYear the optional model year
   */
  public record NhtsaVinDecodeRequest(String vin, Integer modelYear) {}

  /**
   * The NHTSA batch response wrapper.
   *
   * @param Results decoded VIN rows returned by NHTSA
   */
  private record NhtsaResponse(List<Map<String, String>> Results) {}
}

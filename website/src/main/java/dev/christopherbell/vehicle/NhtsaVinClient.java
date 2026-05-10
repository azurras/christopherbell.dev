package dev.christopherbell.vehicle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
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

  public NhtsaVinClient(
      ObjectMapper objectMapper,
      @Value("${vehicles.nhtsa-vin.url:https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVINValuesBatch}") String baseUrl
  ) {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    this.objectMapper = objectMapper;
    this.baseUrl = stripTrailingSlash(baseUrl);
  }

  public Map<String, String> decodeVin(String vin, Integer modelYear)
      throws IOException, InterruptedException, InvalidRequestException, NhtsaVinClientException {
    return decodeVins(List.of(new NhtsaVinDecodeRequest(vin, modelYear))).get(0);
  }

  public List<Map<String, String>> decodeVins(List<NhtsaVinDecodeRequest> vins)
      throws IOException, InterruptedException, InvalidRequestException, NhtsaVinClientException {
    if (vins == null || vins.isEmpty()) {
      throw new InvalidRequestException("VIN batch cannot be empty.");
    }
    if (vins.size() > 50) {
      throw new InvalidRequestException("VIN batch cannot contain more than 50 VINs.");
    }

    var body = "format=json&data=" + URLEncoder.encode(batchData(vins), StandardCharsets.UTF_8);
    var request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .timeout(Duration.ofSeconds(20))
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

  private String batchData(List<NhtsaVinDecodeRequest> vins) throws InvalidRequestException {
    return vins.stream()
        .map(this::batchEntry)
        .collect(Collectors.joining(";"));
  }

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

  private String stripTrailingSlash(String value) {
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  public record NhtsaVinDecodeRequest(String vin, Integer modelYear) {}

  private record NhtsaResponse(List<Map<String, String>> Results) {}
}

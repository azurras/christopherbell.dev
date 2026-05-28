package dev.christopherbell.vehicle.randomvin.importing;

import dev.christopherbell.vehicle.model.VehicleProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

/**
 * Client for retrieving generated VINs from RandomVIN.
 */
@Component
public class RandomVinClient {
  private final HttpClient httpClient;
  private final VehicleProperties.RandomVin properties;
  private final URI randomVinUri;

  /**
   * Creates a RandomVIN client for the configured VIN endpoint.
   *
   * @param vehicleProperties vehicle data collection configuration
   */
  public RandomVinClient(
      VehicleProperties vehicleProperties
  ) {
    this.properties = vehicleProperties.getRandomVin();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getConnectTimeout())
        .build();
    this.randomVinUri = URI.create(properties.getUrl());
  }

  /**
   * Fetches one VIN from RandomVIN.
   *
   * @return the raw RandomVIN response body
   * @throws IOException when the HTTP call fails
   * @throws InterruptedException when the HTTP call is interrupted
   * @throws RandomVinClientException when RandomVIN returns a non-success HTTP status
   */
  public String getVin() throws IOException, InterruptedException, RandomVinClientException {
    var request = HttpRequest.newBuilder(randomVinUri)
        .GET()
        .timeout(properties.getRequestTimeout())
        .header("Accept", "text/plain,text/html")
        .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new RandomVinClientException(response.statusCode());
    }

    return response.body();
  }
}

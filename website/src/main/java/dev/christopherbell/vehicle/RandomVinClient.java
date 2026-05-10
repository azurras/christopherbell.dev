package dev.christopherbell.vehicle;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client for retrieving generated VINs from RandomVIN.
 */
@Component
public class RandomVinClient {
  private final HttpClient httpClient;
  private final URI randomVinUri;

  public RandomVinClient(
      @Value("${vehicles.random-vin.url:https://randomvin.com/getvin.php?type=real}") String randomVinUrl
  ) {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    this.randomVinUri = URI.create(randomVinUrl);
  }

  public String getVin() throws IOException, InterruptedException, RandomVinClientException {
    var request = HttpRequest.newBuilder(randomVinUri)
        .GET()
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "text/plain,text/html")
        .build();

    var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new RandomVinClientException(response.statusCode());
    }

    return response.body();
  }
}

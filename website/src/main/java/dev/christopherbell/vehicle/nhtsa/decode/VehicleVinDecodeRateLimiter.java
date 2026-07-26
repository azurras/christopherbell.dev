package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.vehicle.model.VehicleProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class VehicleVinDecodeRateLimiter {
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final VehicleProperties vehicleProperties;

  public void check(String key) {
    check(key, 1);
  }

  public void check(String key, long tokens) {
    var bucket = buckets.computeIfAbsent(key, ignored -> newBucket());
    if (tokens < 1 || !bucket.tryConsume(tokens)) {
      throw new VehicleVinDecodeRateLimitException("Too many VIN decode requests. Please try again later.");
    }
  }

  private Bucket newBucket() {
    var properties = vehicleProperties.getVinDecoder();
    return Bucket4j.builder()
        .addLimit(Bandwidth.simple(properties.getRateLimitCapacity(), properties.getRateLimitWindow()))
        .build();
  }
}

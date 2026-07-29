package dev.christopherbell.vehicle.nhtsa.decode;

import dev.christopherbell.configuration.filter.RateLimitBucketStore;
import dev.christopherbell.vehicle.model.VehicleProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Applies bounded process-local per-client limits to public VIN decoding. */
@Component
public class VehicleVinDecodeRateLimiter {
  private final VehicleProperties vehicleProperties;
  private final RateLimitBucketStore buckets;

  @Autowired
  public VehicleVinDecodeRateLimiter(VehicleProperties vehicleProperties) {
    this(
        vehicleProperties,
        new RateLimitBucketStore(
            vehicleProperties.getVinDecoder().getMaximumBuckets(),
            System::nanoTime));
  }

  VehicleVinDecodeRateLimiter(
      VehicleProperties vehicleProperties,
      RateLimitBucketStore buckets
  ) {
    this.vehicleProperties = vehicleProperties;
    this.buckets = buckets;
  }

  public void check(String key) {
    check(key, 1);
  }

  public void check(String key, long tokens) {
    var properties = vehicleProperties.getVinDecoder();
    Bucket bucket = buckets.getOrCreate(
        key,
        properties.getRateLimitWindow().multipliedBy(2),
        this::newBucket);
    if (tokens < 1 || !bucket.tryConsume(tokens)) {
      throw new VehicleVinDecodeRateLimitException(
          "Too many VIN decode requests. Please try again later.");
    }
  }

  int bucketCount() {
    return buckets.size();
  }

  private Bucket newBucket() {
    var properties = vehicleProperties.getVinDecoder();
    return Bucket4j.builder()
        .addLimit(Bandwidth.simple(
            properties.getRateLimitCapacity(), properties.getRateLimitWindow()))
        .build();
  }
}

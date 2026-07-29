package dev.christopherbell.vehicle.nhtsa.decode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.christopherbell.configuration.filter.RateLimitBucketStore;
import dev.christopherbell.vehicle.model.VehicleProperties;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class VehicleVinDecodeRateLimiterTest {
  private static final Duration WINDOW = Duration.ofHours(1);
  private final AtomicLong now = new AtomicLong();

  @Test
  void cardinalityNeverExceedsConfiguredMaximum() {
    var limiter = limiter(20, 100);

    IntStream.rangeClosed(0, 100)
        .forEach(index -> limiter.check("client-" + index));

    assertThat(limiter.bucketCount()).isEqualTo(100);
  }

  @Test
  void bucketExpiresAfterTwoInactiveRateLimitWindows() {
    var limiter = limiter(1, 100);
    limiter.check("client");
    now.set(WINDOW.multipliedBy(2).toNanos());

    assertThat(limiter.bucketCount()).isZero();
    limiter.check("client");

    assertThat(limiter.bucketCount()).isEqualTo(1);
  }

  @Test
  void sameKeyReusesItsTokenBucketInsideInactivityWindow() {
    var limiter = limiter(2, 100);
    limiter.check("client");
    now.set(WINDOW.multipliedBy(2).toNanos() - 1);

    limiter.check("client");

    assertThrows(VehicleVinDecodeRateLimitException.class, () -> limiter.check("client"));
    assertThat(limiter.bucketCount()).isEqualTo(1);
  }

  @Test
  void tokenCostConsumesTheRequestedCapacity() {
    var limiter = limiter(5, 100);

    limiter.check("client", 4);
    limiter.check("client", 1);

    assertThrows(VehicleVinDecodeRateLimitException.class, () -> limiter.check("client"));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void invalidTokenCostIsRejected(long tokens) {
    var limiter = limiter(5, 100);

    assertThrows(
        VehicleVinDecodeRateLimitException.class,
        () -> limiter.check("client", tokens));
  }

  @Test
  void concurrentCallsForOneKeyShareOneActiveBucket() throws Exception {
    int participants = 16;
    var limiter = limiter(participants, 100);
    var ready = new CountDownLatch(participants);
    var start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(participants)) {
      var checks = IntStream.range(0, participants)
          .mapToObj(ignored -> executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("VIN limiter concurrency start timed out");
            }
            limiter.check("shared-client");
            return null;
          }))
          .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var check : checks) {
        check.get(5, TimeUnit.SECONDS);
      }
    }

    assertThat(limiter.bucketCount()).isEqualTo(1);
    assertThrows(
        VehicleVinDecodeRateLimitException.class,
        () -> limiter.check("shared-client"));
  }

  @Test
  void springConstructsLimiterWithVehicleProperties() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(VehicleProperties.class, VehicleProperties::new);
      context.register(VehicleVinDecodeRateLimiter.class);

      context.refresh();

      assertThat(context.getBean(VehicleVinDecodeRateLimiter.class)).isNotNull();
    }
  }

  private VehicleVinDecodeRateLimiter limiter(int capacity, int maximumBuckets) {
    var properties = new VehicleProperties();
    properties.getVinDecoder().setRateLimitCapacity(capacity);
    properties.getVinDecoder().setRateLimitWindow(WINDOW);
    properties.getVinDecoder().setMaximumBuckets(maximumBuckets);
    return new VehicleVinDecodeRateLimiter(
        properties,
        new RateLimitBucketStore(maximumBuckets, now::get));
  }
}

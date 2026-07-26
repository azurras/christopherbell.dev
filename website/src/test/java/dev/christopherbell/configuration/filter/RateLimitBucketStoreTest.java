package dev.christopherbell.configuration.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimitBucketStoreTest {
  @Test
  void inactiveBucketsExpireAtTheirRuleWindowWhileRecentBucketsRemain() {
    var now = new AtomicLong();
    var store = new RateLimitBucketStore(10, now::get);
    var first = store.getOrCreate("rule:first", Duration.ofSeconds(5), this::bucket);
    store.getOrCreate("rule:second", Duration.ofSeconds(5), this::bucket);
    now.set(Duration.ofSeconds(4).toNanos());
    assertThat(store.getOrCreate("rule:first", Duration.ofSeconds(5), this::bucket))
        .isSameAs(first);
    now.set(Duration.ofSeconds(5).toNanos());

    store.getOrCreate("rule:third", Duration.ofSeconds(5), this::bucket);

    assertThat(store.contains("rule:first")).isTrue();
    assertThat(store.contains("rule:second")).isFalse();
    assertThat(store.size()).isEqualTo(2);
  }

  @Test
  void activeCardinalityNeverExceedsTheConfiguredMaximum() {
    var store = new RateLimitBucketStore(2, () -> 0L);
    store.getOrCreate("one", Duration.ofMinutes(1), this::bucket);
    store.getOrCreate("two", Duration.ofMinutes(1), this::bucket);
    store.getOrCreate("three", Duration.ofMinutes(1), this::bucket);

    assertThat(store.contains("one")).isFalse();
    assertThat(store.size()).isEqualTo(2);
  }

  @Test
  void extremeWindowDoesNotOverflowExpiryIntoThePast() {
    var store = new RateLimitBucketStore(2, () -> Long.MAX_VALUE - 1);
    var bucket = store.getOrCreate("one", Duration.ofSeconds(Long.MAX_VALUE), this::bucket);

    assertThat(store.getOrCreate("one", Duration.ofSeconds(Long.MAX_VALUE), this::bucket))
        .isSameAs(bucket);
  }

  @Test
  void differentWindowsExpireInDeadlineOrderRatherThanAccessOrder() {
    var now = new AtomicLong();
    var store = new RateLimitBucketStore(10, now::get);
    store.getOrCreate("long", Duration.ofSeconds(10), this::bucket);
    store.getOrCreate("short", Duration.ofSeconds(2), this::bucket);
    store.getOrCreate("medium", Duration.ofSeconds(5), this::bucket);
    now.set(Duration.ofSeconds(3).toNanos());

    store.getOrCreate("new", Duration.ofSeconds(10), this::bucket);

    assertThat(store.contains("long")).isTrue();
    assertThat(store.contains("short")).isFalse();
    assertThat(store.contains("medium")).isTrue();
  }

  private Bucket bucket() {
    return Bucket4j.builder()
        .addLimit(Bandwidth.simple(1, Duration.ofMinutes(1)))
        .build();
  }
}

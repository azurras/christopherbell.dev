package dev.christopherbell.configuration.filter;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Thread-safe, access-ordered ownership boundary for process-local rate-limit buckets. */
public final class RateLimitBucketStore {
  private final int maximumSize;
  private final LongSupplier nanoTime;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);

  public RateLimitBucketStore(int maximumSize, LongSupplier nanoTime) {
    if (maximumSize <= 0) {
      throw new IllegalArgumentException("maximum bucket count must be positive");
    }
    this.maximumSize = maximumSize;
    this.nanoTime = nanoTime;
  }

  /** Returns the active bucket or atomically creates one with sliding inactivity expiry. */
  public synchronized Bucket getOrCreate(
      String key,
      Duration inactivityWindow,
      Supplier<Bucket> factory
  ) {
    long now = nanoTime.getAsLong();
    long inactivityNanos = positiveNanos(inactivityWindow);
    removeExpired(now);
    Entry existing = entries.get(key);
    if (existing != null) {
      existing.lastAccessNanos = now;
      existing.inactivityNanos = inactivityNanos;
      return existing.bucket;
    }

    Bucket bucket = factory.get();
    entries.put(key, new Entry(bucket, now, inactivityNanos));
    while (entries.size() > maximumSize) {
      Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
      iterator.next();
      iterator.remove();
    }
    return bucket;
  }

  synchronized boolean contains(String key) {
    return entries.containsKey(key);
  }

  synchronized int size() {
    return entries.size();
  }

  private void removeExpired(long now) {
    entries.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
  }

  private boolean isExpired(Entry entry, long now) {
    long elapsed = now - entry.lastAccessNanos;
    return elapsed >= 0 && elapsed >= entry.inactivityNanos;
  }

  private long positiveNanos(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("bucket inactivity window must be positive");
    }
    try {
      return duration.toNanos();
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private static final class Entry {
    private final Bucket bucket;
    private long lastAccessNanos;
    private long inactivityNanos;

    private Entry(Bucket bucket, long lastAccessNanos, long inactivityNanos) {
      this.bucket = bucket;
      this.lastAccessNanos = lastAccessNanos;
      this.inactivityNanos = inactivityNanos;
    }
  }
}

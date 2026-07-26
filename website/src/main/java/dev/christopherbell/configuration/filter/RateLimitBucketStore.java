package dev.christopherbell.configuration.filter;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Thread-safe, access-ordered ownership boundary for process-local rate-limit buckets. */
public final class RateLimitBucketStore {
  private static final Comparator<Entry> EXPIRY_ORDER =
      Comparator.comparingLong((Entry entry) -> entry.expiresAtNanos)
          .thenComparing(entry -> entry.key);

  private final int maximumSize;
  private final LongSupplier nanoTime;
  private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
  private final NavigableSet<Entry> expirations = new TreeSet<>(EXPIRY_ORDER);

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
      expirations.remove(existing);
      existing.expiresAtNanos = saturatingAdd(now, inactivityNanos);
      expirations.add(existing);
      return existing.bucket;
    }

    Bucket bucket = factory.get();
    Entry added = new Entry(key, bucket, saturatingAdd(now, inactivityNanos));
    entries.put(key, added);
    expirations.add(added);
    while (entries.size() > maximumSize) {
      Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
      Entry eldest = iterator.next().getValue();
      iterator.remove();
      expirations.remove(eldest);
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
    while (!expirations.isEmpty()) {
      Entry next = expirations.first();
      if (next.expiresAtNanos > now) {
        return;
      }
      expirations.pollFirst();
      entries.remove(next.key);
    }
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

  private long saturatingAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private static final class Entry {
    private final String key;
    private final Bucket bucket;
    private long expiresAtNanos;

    private Entry(String key, Bucket bucket, long expiresAtNanos) {
      this.key = key;
      this.bucket = bucket;
      this.expiresAtNanos = expiresAtNanos;
    }
  }
}

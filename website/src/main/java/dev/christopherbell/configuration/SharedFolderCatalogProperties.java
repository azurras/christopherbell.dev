package dev.christopherbell.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Explicit resource and freshness budgets for asynchronous shared-folder catalog scans. */
@Validated
@ConfigurationProperties("app.shared-folder.catalog")
public record SharedFolderCatalogProperties(
    int maxEntries,
    int maxDirectories,
    int maxDepth,
    Duration maxScanDuration,
    Duration refreshAfter,
    int defaultPageSize) {
  private static final int MAX_ENTRY_LIMIT = 1_000_000;
  private static final int MAX_DIRECTORY_LIMIT = 100_000;
  private static final int MAX_DEPTH_LIMIT = 128;
  private static final Duration MAX_SCAN_LIMIT = Duration.ofMinutes(10);
  private static final Duration MAX_REFRESH_LIMIT = Duration.ofHours(1);

  /** Rejects unsafe or accidentally unbounded scan settings during configuration binding. */
  public SharedFolderCatalogProperties {
    requireRange(maxEntries, 1, MAX_ENTRY_LIMIT, "entry limit");
    requireRange(maxDirectories, 1, MAX_DIRECTORY_LIMIT, "directory limit");
    requireRange(maxDepth, 0, MAX_DEPTH_LIMIT, "depth limit");
    requireDuration(maxScanDuration, MAX_SCAN_LIMIT, "scan duration");
    requireDuration(refreshAfter, MAX_REFRESH_LIMIT, "refresh age");
    requireRange(defaultPageSize, 1, 100, "default page size");
  }

  private static void requireRange(int value, int minimum, int maximum, String label) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException("Shared-folder catalog " + label + " is invalid");
    }
  }

  private static void requireDuration(Duration value, Duration maximum, String label) {
    if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("Shared-folder catalog " + label + " is invalid");
    }
  }
}

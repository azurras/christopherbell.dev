package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderSearchResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Builds a short-lived public-safe catalog through the shared-folder read boundary. */
@Service
public class SharedFolderCatalogService {
  private static final Duration SNAPSHOT_LIFETIME = Duration.ofSeconds(15);
  private static final int MAX_QUERY_LENGTH = 200;
  private static final int MAX_RESULTS = 200;

  private final SharedFolderBrowserService browser;
  private final Clock clock;
  private volatile CatalogSnapshot snapshot;

  /** Creates the catalog with its filesystem boundary and a clock for bounded freshness. */
  public SharedFolderCatalogService(SharedFolderBrowserService browser, Clock clock) {
    this.browser = browser;
    this.clock = clock;
  }

  /** Finds matching public-safe entries from an immutable catalog no older than fifteen seconds. */
  public SharedFolderSearchResponse search(String query) {
    String validatedQuery = requireQuery(query);
    String normalizedQuery = validatedQuery.toLowerCase(Locale.ROOT);
    List<SharedDirectoryEntry> matches = new ArrayList<>();
    boolean truncated = false;
    for (SharedDirectoryEntry entry : currentSnapshot().entries()) {
      if (!matches(entry, normalizedQuery)) {
        continue;
      }
      if (matches.size() == MAX_RESULTS) {
        truncated = true;
        break;
      }
      matches.add(entry);
    }
    return new SharedFolderSearchResponse(validatedQuery, matches, truncated);
  }

  private CatalogSnapshot currentSnapshot() {
    Instant now = clock.instant();
    CatalogSnapshot current = snapshot;
    if (current != null && current.isCurrentAt(now)) {
      return current;
    }
    synchronized (this) {
      current = snapshot;
      if (current != null && current.isCurrentAt(now)) {
        return current;
      }
      CatalogSnapshot refreshed = new CatalogSnapshot(now, listBreadthFirst());
      snapshot = refreshed;
      return refreshed;
    }
  }

  private List<SharedDirectoryEntry> listBreadthFirst() {
    ArrayDeque<String> directories = new ArrayDeque<>();
    List<SharedDirectoryEntry> entries = new ArrayList<>();
    directories.add("");
    while (!directories.isEmpty()) {
      for (SharedDirectoryEntry entry : browser.list(directories.remove()).entries()) {
        entries.add(entry);
        if (entry.type() == SharedDirectoryEntryType.DIRECTORY) {
          directories.add(entry.path());
        }
      }
    }
    return List.copyOf(entries);
  }

  private boolean matches(SharedDirectoryEntry entry, String normalizedQuery) {
    return entry.name().toLowerCase(Locale.ROOT).contains(normalizedQuery)
        || entry.path().toLowerCase(Locale.ROOT).contains(normalizedQuery);
  }

  private String requireQuery(String query) {
    if (query == null) {
      throw invalidQuery();
    }
    String trimmed = query.strip();
    if (trimmed.isEmpty() || trimmed.length() > MAX_QUERY_LENGTH) {
      throw invalidQuery();
    }
    return trimmed;
  }

  private ResponseStatusException invalidQuery() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "Search query must contain between 1 and 200 characters");
  }

  private record CatalogSnapshot(Instant createdAt, List<SharedDirectoryEntry> entries) {
    private CatalogSnapshot {
      entries = List.copyOf(entries);
    }

    private boolean isCurrentAt(Instant now) {
      return createdAt.plus(SNAPSHOT_LIFETIME).isAfter(now);
    }
  }
}

package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.configuration.SharedFolderCatalogProperties;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntryType;
import dev.christopherbell.sharedfolder.model.SharedFolderCatalogFreshness;
import dev.christopherbell.sharedfolder.model.SharedFolderCatalogStatus;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.model.SharedFolderSearchRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderSearchResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Builds bounded immutable shared-folder catalog generations outside request threads. */
@Service
public final class SharedFolderCatalogService {
  private static final int MAX_QUERY_LENGTH = 200;
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_CURSOR_LENGTH = 4096;
  private static final String CURSOR_VERSION = "1";
  private static final Comparator<SharedDirectoryEntry> STABLE_ORDER = Comparator
      .comparing((SharedDirectoryEntry entry) -> entry.path().toLowerCase(Locale.ROOT))
      .thenComparing(SharedDirectoryEntry::path)
      .thenComparing(entry -> entry.type().name())
      .thenComparing(entry -> entry.observedToken() == null ? "" : entry.observedToken());

  private final SharedFolderBrowserService browser;
  private final Clock clock;
  private final SharedFolderCatalogProperties properties;
  private final ExecutorService executor;
  private final AtomicLong requestedGeneration = new AtomicLong(1);
  private final Object schedulingLock = new Object();
  private volatile CatalogSnapshot snapshot;
  private volatile Future<?> running;
  private volatile long failedGeneration;

  /** Creates the asynchronous catalog around a dedicated bounded worker. */
  public SharedFolderCatalogService(
      SharedFolderBrowserService browser,
      Clock clock,
      SharedFolderCatalogProperties properties,
      @Qualifier("sharedFolderCatalogExecutor") ExecutorService executor) {
    if (browser == null || clock == null || properties == null || executor == null) {
      throw new IllegalArgumentException("Shared-folder catalog dependencies are required");
    }
    this.browser = browser;
    this.clock = clock;
    this.properties = properties;
    this.executor = executor;
  }

  /** Returns a generation-bound page in stable relative-path order. */
  public SharedFolderSearchResponse search(SharedFolderSearchRequest request) {
    if (request == null) throw invalidQuery();
    String validatedQuery = requireQuery(request.query());
    int pageSize = requirePageSize(request.size());
    String normalizedQuery = validatedQuery.toLowerCase(Locale.ROOT);
    CatalogSnapshot current = currentSnapshot();
    SearchCursor cursor = decodeCursor(request.cursor(), normalizedQuery);
    if (cursor != null && cursor.generation() != current.generation()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SHARED_FOLDER_CATALOG_CHANGED");
    }

    List<SharedDirectoryEntry> matches = new ArrayList<>(pageSize + 1);
    for (SharedDirectoryEntry entry : current.entries()) {
      if (cursor != null && comparePaths(entry.path(), cursor.path()) <= 0) continue;
      if (!matches(entry, normalizedQuery)) continue;
      matches.add(entry);
      if (matches.size() > pageSize) break;
    }
    String nextCursor = null;
    if (matches.size() > pageSize) {
      matches.remove(matches.size() - 1);
      nextCursor = encodeCursor(new SearchCursor(
          current.generation(), queryHash(normalizedQuery), matches.get(matches.size() - 1).path()));
    }
    SharedFolderCatalogStatus status = status();
    return new SharedFolderSearchResponse(
        validatedQuery, matches, nextCursor, current.generation(), current.createdAt(),
        status.freshness(), current.partial());
  }

  /** Compatibility entry point for internal callers that need only the first page. */
  public SharedFolderSearchResponse search(String query) {
    return search(new SharedFolderSearchRequest(query, null, properties.defaultPageSize()));
  }

  /** Returns audio tracks from the latest snapshot and schedules refresh when necessary. */
  public List<SharedDirectoryEntry> audioTracksBelowMusic() {
    return currentSnapshot().entries().stream()
        .filter(this::isAudioTrackBelowMusic)
        .toList();
  }

  /** Reports public-safe snapshot state without exposing filesystem or exception details. */
  public SharedFolderCatalogStatus status() {
    CatalogSnapshot current = snapshot;
    long requested = requestedGeneration.get();
    if (current == null) {
      return new SharedFolderCatalogStatus(
          requested, null,
          failedGeneration == requested
              ? SharedFolderCatalogFreshness.FAILED : SharedFolderCatalogFreshness.BUILDING,
          false, 0, failedGeneration == requested ? "scan_failed" : null);
    }
    boolean failed = failedGeneration == requested && current.generation() != requested;
    SharedFolderCatalogFreshness freshness = failed
        ? SharedFolderCatalogFreshness.FAILED
        : current.isCurrentAt(clock.instant(), properties.refreshAfter())
            ? SharedFolderCatalogFreshness.FRESH : SharedFolderCatalogFreshness.STALE;
    return new SharedFolderCatalogStatus(
        current.generation(), current.createdAt(), freshness, current.partial(),
        current.entries().size(), failed ? "scan_failed" : null);
  }

  /** Schedules the requested generation when no worker currently owns it. */
  public void refreshAsync() {
    synchronized (schedulingLock) {
      if (running != null && !running.isDone()) return;
      long generation = requestedGeneration.get();
      running = executor.submit(() -> refresh(generation));
    }
  }

  /** Advances generation, cancels obsolete work, and schedules the replacement. */
  public void invalidate(SharedFolderCatalogInvalidation reason) {
    if (reason == null) throw new IllegalArgumentException("Catalog invalidation reason is required");
    synchronized (schedulingLock) {
      requestedGeneration.incrementAndGet();
      if (running != null && !running.isDone()) running.cancel(true);
      running = null;
    }
    refreshAsync();
  }

  private CatalogSnapshot currentSnapshot() {
    CatalogSnapshot current = snapshot;
    if (current == null || !current.isCurrentAt(clock.instant(), properties.refreshAfter())) {
      refreshAsync();
    }
    return current == null ? CatalogSnapshot.empty(requestedGeneration.get(), clock.instant()) : current;
  }

  private void refresh(long generation) {
    try {
      ScanResult result = scan(generation);
      if (!result.cancelled() && requestedGeneration.get() == generation) {
        snapshot = new CatalogSnapshot(
            generation, clock.instant(), result.entries(), result.partial());
        failedGeneration = 0;
      }
    } catch (RuntimeException failure) {
      if (requestedGeneration.get() == generation) failedGeneration = generation;
    }
  }

  private ScanResult scan(long generation) {
    Instant deadline = clock.instant().plus(properties.maxScanDuration());
    ArrayDeque<DirectoryWork> directories = new ArrayDeque<>();
    List<SharedDirectoryEntry> entries = new ArrayList<>();
    directories.add(new DirectoryWork("", 0));
    int listedDirectories = 0;
    boolean partial = false;

    while (!directories.isEmpty()) {
      if (cancelled(generation)) return ScanResult.cancelledResult();
      if (listedDirectories >= properties.maxDirectories()) {
        partial = true;
        break;
      }
      DirectoryWork work = directories.removeFirst();
      List<SharedDirectoryEntry> children;
      try {
        children = browser.list(work.path()).entries();
      } catch (RuntimeException failure) {
        if (work.path().isEmpty()) throw failure;
        partial = true;
        continue;
      }
      listedDirectories++;
      for (SharedDirectoryEntry entry : children) {
        if (cancelled(generation)) return ScanResult.cancelledResult();
        if (entries.size() >= properties.maxEntries()) {
          partial = true;
          break;
        }
        entries.add(entry);
        if (entry.type() != SharedDirectoryEntryType.DIRECTORY) continue;
        int childDepth = work.depth() + 1;
        if (childDepth > properties.maxDepth()) {
          partial = true;
        } else {
          directories.addLast(new DirectoryWork(entry.path(), childDepth));
        }
      }
      if (entries.size() >= properties.maxEntries()) break;
      if (!clock.instant().isBefore(deadline)) {
        partial = partial || !directories.isEmpty();
        break;
      }
    }
    entries.sort(STABLE_ORDER);
    return new ScanResult(List.copyOf(entries), partial, false);
  }

  private boolean cancelled(long generation) {
    return Thread.currentThread().isInterrupted() || requestedGeneration.get() != generation;
  }

  private boolean matches(SharedDirectoryEntry entry, String normalizedQuery) {
    return entry.name().toLowerCase(Locale.ROOT).contains(normalizedQuery)
        || entry.path().toLowerCase(Locale.ROOT).contains(normalizedQuery);
  }

  private boolean isAudioTrackBelowMusic(SharedDirectoryEntry entry) {
    if (entry.type() != SharedDirectoryEntryType.FILE
        || entry.previewKind() != SharedFolderPreviewKind.AUDIO) return false;
    int separator = entry.path().indexOf('/');
    return separator > 0 && entry.path().substring(0, separator).equalsIgnoreCase("Music");
  }

  private String requireQuery(String query) {
    if (query == null) throw invalidQuery();
    String trimmed = query.strip();
    if (trimmed.isEmpty() || trimmed.length() > MAX_QUERY_LENGTH
        || trimmed.chars().anyMatch(Character::isISOControl)) throw invalidQuery();
    return trimmed;
  }

  private int requirePageSize(Integer size) {
    int resolved = size == null ? properties.defaultPageSize() : size;
    if (resolved < 1 || resolved > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Search page size must contain between 1 and 100 entries");
    }
    return resolved;
  }

  private int comparePaths(String left, String right) {
    int folded = left.toLowerCase(Locale.ROOT).compareTo(right.toLowerCase(Locale.ROOT));
    return folded != 0 ? folded : left.compareTo(right);
  }

  private String encodeCursor(SearchCursor cursor) {
    String value = String.join("\n", CURSOR_VERSION, Long.toString(cursor.generation()),
        cursor.queryHash(), cursor.path());
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private SearchCursor decodeCursor(String encoded, String normalizedQuery) {
    if (encoded == null || encoded.isBlank()) return null;
    if (encoded.length() > MAX_CURSOR_LENGTH || !encoded.equals(encoded.strip())) {
      throw invalidCursor(null);
    }
    try {
      String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
      String[] parts = value.split("\n", -1);
      if (parts.length != 4 || !CURSOR_VERSION.equals(parts[0]) || parts[3].isBlank()
          || parts[3].chars().anyMatch(Character::isISOControl)) throw invalidCursor(null);
      long generation = Long.parseLong(parts[1]);
      if (generation < 1 || !MessageDigest.isEqual(
          parts[2].getBytes(StandardCharsets.US_ASCII),
          queryHash(normalizedQuery).getBytes(StandardCharsets.US_ASCII))) {
        throw invalidCursor(null);
      }
      return new SearchCursor(generation, parts[2], parts[3]);
    } catch (IllegalArgumentException failure) {
      throw invalidCursor(failure);
    }
  }

  private String queryHash(String normalizedQuery) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(normalizedQuery.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private ResponseStatusException invalidCursor(Throwable cause) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search cursor", cause);
  }

  private ResponseStatusException invalidQuery() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "Search query must contain between 1 and 200 characters");
  }

  private record DirectoryWork(String path, int depth) {}

  private record SearchCursor(long generation, String queryHash, String path) {}

  private record ScanResult(List<SharedDirectoryEntry> entries, boolean partial, boolean cancelled) {
    private static ScanResult cancelledResult() {
      return new ScanResult(List.of(), false, true);
    }
  }

  private record CatalogSnapshot(
      long generation, Instant createdAt, List<SharedDirectoryEntry> entries, boolean partial) {
    private CatalogSnapshot {
      entries = List.copyOf(entries);
    }

    private static CatalogSnapshot empty(long generation, Instant now) {
      return new CatalogSnapshot(generation, now, List.of(), false);
    }

    private boolean isCurrentAt(Instant now, java.time.Duration refreshAfter) {
      return createdAt.plus(refreshAfter).isAfter(now);
    }
  }
}

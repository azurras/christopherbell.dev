package dev.christopherbell.sharedfolder.model;

import java.time.Instant;
import java.util.List;

/** One stable page from an immutable shared-folder catalog generation. */
public record SharedFolderSearchResponse(
    String query,
    List<SharedDirectoryEntry> entries,
    String nextCursor,
    long generation,
    Instant snapshotCreatedAt,
    SharedFolderCatalogFreshness freshness,
    boolean partial) {
  public SharedFolderSearchResponse {
    entries = List.copyOf(entries);
  }
}

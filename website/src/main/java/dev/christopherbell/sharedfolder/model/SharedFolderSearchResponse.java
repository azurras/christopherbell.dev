package dev.christopherbell.sharedfolder.model;

import java.util.List;

/** Public-safe shared-folder entries that match one validated search query. */
public record SharedFolderSearchResponse(
    String query, List<SharedDirectoryEntry> entries, boolean truncated) {
  public SharedFolderSearchResponse {
    entries = List.copyOf(entries);
  }
}

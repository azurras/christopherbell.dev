package dev.christopherbell.sharedfolder.model;

import java.time.Instant;

/** Public-safe metadata for one shared-folder entry. */
public record SharedDirectoryEntry(
    String name,
    String path,
    SharedDirectoryEntryType type,
    long size,
    Instant modifiedAt,
    SharedFolderPreviewKind previewKind,
    String observedToken) {

  /** Preserves the read-only response construction used before observations were introduced. */
  public SharedDirectoryEntry(
      String name,
      String path,
      SharedDirectoryEntryType type,
      long size,
      Instant modifiedAt,
      SharedFolderPreviewKind previewKind) {
    this(name, path, type, size, modifiedAt, previewKind, null);
  }
}

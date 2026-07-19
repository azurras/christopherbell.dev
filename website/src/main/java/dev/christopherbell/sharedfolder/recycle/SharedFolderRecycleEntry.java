package dev.christopherbell.sharedfolder.recycle;

import java.time.Instant;

/** Public ADMIN recycle view without private payload keys or filesystem fingerprints. */
public record SharedFolderRecycleEntry(
    String id,
    String originalPath,
    String deletedByAccountId,
    Instant deletedAt,
    Instant expiresAt,
    long size,
    boolean directory) {
  public static SharedFolderRecycleEntry from(SharedFolderRecycleItem item) {
    return new SharedFolderRecycleEntry(
        item.id(), item.originalPath(), item.deletedByAccountId(), item.deletedAt(),
        item.expiresAt(), item.size(), item.directory());
  }
}

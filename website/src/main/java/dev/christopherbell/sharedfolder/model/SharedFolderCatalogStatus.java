package dev.christopherbell.sharedfolder.model;

import java.time.Instant;

/** Public-safe status for one bounded immutable shared-folder catalog snapshot. */
public record SharedFolderCatalogStatus(
    long generation,
    Instant createdAt,
    SharedFolderCatalogFreshness freshness,
    boolean partial,
    int entryCount,
    String failureCategory) {}

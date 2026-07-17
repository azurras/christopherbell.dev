package dev.christopherbell.sharedfolder.upload;

import java.time.Instant;

/** Public-safe upload progress response; it deliberately omits private staging location details. */
public record SharedFolderUploadStatus(
    String id,
    String parentPath,
    String name,
    long expectedBytes,
    long nextOffset,
    SharedFolderUploadState state,
    Instant expiresAt) {}

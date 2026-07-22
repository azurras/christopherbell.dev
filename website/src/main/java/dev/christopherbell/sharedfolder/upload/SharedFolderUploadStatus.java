package dev.christopherbell.sharedfolder.upload;

import java.time.Instant;
import java.util.List;

/** Public-safe upload progress response; it deliberately omits private staging location details. */
public record SharedFolderUploadStatus(
    String id,
    String parentPath,
    String name,
    long expectedBytes,
    long nextOffset,
    SharedFolderUploadState state,
    Instant expiresAt,
    long chunkSizeBytes,
    List<SharedFolderUploadChunkProof> committedChunks) {

  /** Compatibility constructor for controller tests that do not need resume proofs. */
  public SharedFolderUploadStatus(
      String id,
      String parentPath,
      String name,
      long expectedBytes,
      long nextOffset,
      SharedFolderUploadState state,
      Instant expiresAt) {
    this(id, parentPath, name, expectedBytes, nextOffset, state, expiresAt, 0, List.of());
  }
}

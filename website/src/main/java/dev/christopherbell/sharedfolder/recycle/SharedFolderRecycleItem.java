package dev.christopherbell.sharedfolder.recycle;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/** Private recycle metadata. Absolute host paths are deliberately absent. */
@Document("shared_folder_recycle_items")
@CompoundIndexes({
    @CompoundIndex(name = "shared_recycle_state_deleted_desc",
        def = "{'state': 1, 'deletedAt': -1}"),
    @CompoundIndex(name = "shared_recycle_state_expiry",
        def = "{'state': 1, 'expiresAt': 1}")
})
public record SharedFolderRecycleItem(
    @Id String id,
    String originalPath,
    String deletedByAccountId,
    Instant deletedAt,
    Instant expiresAt,
    String payloadKey,
    long size,
    boolean directory,
    String sourceFingerprint,
    SharedFolderRecycleState state,
    String replacementKey,
    String replacementFingerprint) {

  /** Compatibility constructor for records without an active replacement journal. */
  public SharedFolderRecycleItem(
      String id, String originalPath, String deletedByAccountId, Instant deletedAt,
      Instant expiresAt, String payloadKey, long size, boolean directory,
      String sourceFingerprint, SharedFolderRecycleState state) {
    this(id, originalPath, deletedByAccountId, deletedAt, expiresAt, payloadKey, size, directory,
        sourceFingerprint, state, null, null);
  }

  public SharedFolderRecycleItem {
    if (id == null || id.isBlank() || originalPath == null || originalPath.isBlank()
        || deletedByAccountId == null || deletedByAccountId.isBlank()
        || deletedAt == null || expiresAt == null || payloadKey == null || payloadKey.isBlank()
        || size < 0 || sourceFingerprint == null || sourceFingerprint.isBlank() || state == null) {
      throw new IllegalArgumentException("Recycle metadata is incomplete");
    }
    if ((replacementKey == null) != (replacementFingerprint == null)) {
      throw new IllegalArgumentException("Replacement journal is incomplete");
    }
  }

  public SharedFolderRecycleItem withState(SharedFolderRecycleState next) {
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, expiresAt,
        payloadKey, size, directory, sourceFingerprint, next, replacementKey,
        replacementFingerprint);
  }

  public SharedFolderRecycleItem withExpiresAt(Instant nextExpiry) {
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, nextExpiry,
        payloadKey, size, directory, sourceFingerprint, state, replacementKey,
        replacementFingerprint);
  }

  public SharedFolderRecycleItem withRestore(String key, String fingerprint) {
    if ((key == null) != (fingerprint == null)) {
      throw new IllegalArgumentException("Replacement journal is incomplete");
    }
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, expiresAt,
        payloadKey, size, directory, sourceFingerprint, SharedFolderRecycleState.RESTORING,
        key, fingerprint);
  }

  public SharedFolderRecycleItem recycledAgain() {
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, expiresAt,
        payloadKey, size, directory, sourceFingerprint, SharedFolderRecycleState.RECYCLED,
        null, null);
  }
}

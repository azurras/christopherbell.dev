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
        def = "{'state': 1, 'deletedAt': -1, '_id': -1}"),
    @CompoundIndex(name = "shared_recycle_state_recovery_due",
        def = "{'state': 1, 'deletedAt': 1, '_id': 1, 'retryAfter': 1}"),
    @CompoundIndex(name = "shared_recycle_state_expiry",
        def = "{'state': 1, 'expiresAt': 1, '_id': 1, 'retryAfter': 1}")
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
    String replacementFingerprint,
    String sourceIdentity,
    Instant retryAfter) {

  /** Compatibility constructor for records without a deferred maintenance retry. */
  public SharedFolderRecycleItem(
      String id, String originalPath, String deletedByAccountId, Instant deletedAt,
      Instant expiresAt, String payloadKey, long size, boolean directory,
      String sourceFingerprint, SharedFolderRecycleState state,
      String replacementKey, String replacementFingerprint, String sourceIdentity) {
    this(id, originalPath, deletedByAccountId, deletedAt, expiresAt, payloadKey, size, directory,
        sourceFingerprint, state, replacementKey, replacementFingerprint, sourceIdentity,
        Instant.EPOCH);
  }

  /** Compatibility constructor for records without an active replacement journal. */
  public SharedFolderRecycleItem(
      String id, String originalPath, String deletedByAccountId, Instant deletedAt,
      Instant expiresAt, String payloadKey, long size, boolean directory,
      String sourceFingerprint, SharedFolderRecycleState state) {
    this(id, originalPath, deletedByAccountId, deletedAt, expiresAt, payloadKey, size, directory,
        sourceFingerprint, state, null, null, sourceFingerprint, Instant.EPOCH);
  }

  /** Compatibility constructor for records created before stable recovery identity was explicit. */
  public SharedFolderRecycleItem(
      String id, String originalPath, String deletedByAccountId, Instant deletedAt,
      Instant expiresAt, String payloadKey, long size, boolean directory,
      String sourceFingerprint, SharedFolderRecycleState state,
      String replacementKey, String replacementFingerprint) {
    this(id, originalPath, deletedByAccountId, deletedAt, expiresAt, payloadKey, size, directory,
        sourceFingerprint, state, replacementKey, replacementFingerprint, sourceFingerprint,
        Instant.EPOCH);
  }

  public SharedFolderRecycleItem {
    if (id == null || id.isBlank() || originalPath == null || originalPath.isBlank()
        || deletedByAccountId == null || deletedByAccountId.isBlank()
        || deletedAt == null || expiresAt == null || payloadKey == null || payloadKey.isBlank()
        || size < 0 || sourceFingerprint == null || sourceFingerprint.isBlank()
        || sourceIdentity == null || sourceIdentity.isBlank() || state == null
        || retryAfter == null) {
      throw new IllegalArgumentException("Recycle metadata is incomplete");
    }
    if ((replacementKey == null) != (replacementFingerprint == null)) {
      throw new IllegalArgumentException("Replacement journal is incomplete");
    }
  }

  public SharedFolderRecycleItem withState(SharedFolderRecycleState next) {
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, expiresAt,
        payloadKey, size, directory, sourceFingerprint, next, replacementKey,
        replacementFingerprint, sourceIdentity, retryAfter);
  }

  public SharedFolderRecycleItem withExpiresAt(Instant nextExpiry) {
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, nextExpiry,
        payloadKey, size, directory, sourceFingerprint, state, replacementKey,
        replacementFingerprint, sourceIdentity, retryAfter);
  }

  public SharedFolderRecycleItem withRestore(String key, String fingerprint) {
    if ((key == null) != (fingerprint == null)) {
      throw new IllegalArgumentException("Replacement journal is incomplete");
    }
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, expiresAt,
        payloadKey, size, directory, sourceFingerprint, SharedFolderRecycleState.RESTORING,
        key, fingerprint, sourceIdentity, retryAfter);
  }

  public SharedFolderRecycleItem recycledAgain() {
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, expiresAt,
        payloadKey, size, directory, sourceFingerprint, SharedFolderRecycleState.RECYCLED,
        null, null, sourceIdentity, Instant.EPOCH);
  }

  public SharedFolderRecycleItem withRetryAfter(Instant nextRetry) {
    return new SharedFolderRecycleItem(id, originalPath, deletedByAccountId, deletedAt, expiresAt,
        payloadKey, size, directory, sourceFingerprint, state, replacementKey,
        replacementFingerprint, sourceIdentity, nextRetry);
  }
}

package dev.christopherbell.configuration.persistence.migration;

import java.util.List;

/** Durable per-kind progress; a batch and its cursor must be committed atomically. */
public record MigrationCheckpoint(
    String cursor, boolean complete, long sourceCount, String sourceDigest) {
  private static final String EMPTY_DIGEST = CanonicalMigrationHasher.sha256(List.of());

  public MigrationCheckpoint {
    if (sourceCount < 0 || sourceDigest == null || !sourceDigest.matches("[0-9a-f]{64}")
        || complete && cursor == null && sourceCount != 0) {
      throw new IllegalArgumentException("PostgreSQL migration checkpoint is invalid.");
    }
  }

  public static MigrationCheckpoint initial() {
    return new MigrationCheckpoint(null, false, 0, EMPTY_DIGEST);
  }

  public MigrationCheckpoint advance(
      String nextCursor, List<TransformedMigrationDocument> documents) {
    if (complete || nextCursor == null || nextCursor.isBlank() || documents.isEmpty()
        || nextCursor.equals(cursor)) {
      throw new IllegalStateException("PostgreSQL migration checkpoint cannot advance.");
    }
    var digest = sourceDigest;
    for (var document : documents) {
      digest = CanonicalMigrationHasher.sha256(List.of(digest, document.sourceHash()));
    }
    return new MigrationCheckpoint(nextCursor, false, sourceCount + documents.size(), digest);
  }

  public MigrationCheckpoint markComplete() {
    if (complete) {
      return this;
    }
    return new MigrationCheckpoint(cursor, true, sourceCount, sourceDigest);
  }
}

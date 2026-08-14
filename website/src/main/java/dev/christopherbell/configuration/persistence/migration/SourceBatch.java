package dev.christopherbell.configuration.persistence.migration;

import java.util.List;

/** One source page in strict source-id order. */
public record SourceBatch(List<MigrationSourceDocument> documents, String lastCursor) {
  public SourceBatch {
    documents = List.copyOf(documents);
    if (documents.isEmpty() != (lastCursor == null)) {
      throw new IllegalArgumentException("PostgreSQL migration source batch is invalid.");
    }
  }

  public static SourceBatch of(List<MigrationSourceDocument> documents) {
    var copy = List.copyOf(documents);
    return new SourceBatch(copy, copy.isEmpty() ? null : copy.getLast().sourceId());
  }

  public boolean isEmpty() {
    return documents.isEmpty();
  }
}

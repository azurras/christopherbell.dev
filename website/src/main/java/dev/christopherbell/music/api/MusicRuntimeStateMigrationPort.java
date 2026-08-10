package dev.christopherbell.music.api;

import java.util.List;
import org.bson.Document;

/** Validates and maps raw Music runtime-state BSON for the configuration-owned migration. */
public interface MusicRuntimeStateMigrationPort {
  /**
   * Validates both legacy source collections before the configuration migration reads its target.
   */
  PreparedMigration prepare(List<Document> queueSources, List<Document> radioSources);

  /** A source-validated migration plan that exposes only raw target BSON operations. */
  interface PreparedMigration {
    /**
     * Validates current target membership.
     *
     * @return canonical ordered target documents when an empty target needs insertion, otherwise
     *     an empty list
     */
    List<Document> documentsToInsert(List<Document> existingTargets);

    /** Requires a post-insert target readback to be exactly equivalent to the prepared sources. */
    void requireEquivalent(List<Document> targetDocuments);
  }
}

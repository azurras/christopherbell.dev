package dev.christopherbell.configuration.persistence.migration;

import java.util.UUID;

/** Evidence binding a stopped-writer source to one exact finalization request. */
public record FrozenSourceEvidence(
    String release,
    String catalogDigest,
    String sourceDatabase,
    String targetDatabase,
    String sourceDigest,
    String backupDigest,
    UUID lockToken,
    String sourceUri,
    String targetJdbcUrl,
    String targetRole,
    String writerLockDigest,
    String evidenceDigest) {
  public String reconstructedDigest() {
    return CanonicalMigrationHasher.sha256(java.util.Map.ofEntries(
        java.util.Map.entry("release", release),
        java.util.Map.entry("catalogDigest", catalogDigest),
        java.util.Map.entry("sourceDatabase", sourceDatabase),
        java.util.Map.entry("targetDatabase", targetDatabase),
        java.util.Map.entry("sourceDigest", sourceDigest),
        java.util.Map.entry("backupDigest", backupDigest),
        java.util.Map.entry("lockToken", lockToken.toString()),
        java.util.Map.entry("sourceUri", sourceUri),
        java.util.Map.entry("targetJdbcUrl", targetJdbcUrl),
        java.util.Map.entry("targetRole", targetRole),
        java.util.Map.entry("writerLockDigest", writerLockDigest)));
  }
}

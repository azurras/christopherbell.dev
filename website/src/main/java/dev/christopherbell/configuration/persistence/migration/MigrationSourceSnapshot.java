package dev.christopherbell.configuration.persistence.migration;

import java.util.LinkedHashMap;
import java.util.List;

/** Independent Mongo reread evidence, including exact transformed relational rows. */
public record MigrationSourceSnapshot(
    String sourceKind, long sourceCount, String sourceDigest, String relationalDigest) {
  public MigrationSourceSnapshot {
    if (sourceKind == null || sourceKind.isBlank() || sourceCount < 0
        || !digest(sourceDigest) || !digest(relationalDigest)) {
      throw new IllegalArgumentException("PostgreSQL migration source snapshot is invalid.");
    }
  }

  public static String runDigest(List<MigrationSourceSnapshot> snapshots) {
    return CanonicalMigrationHasher.sha256(snapshots.stream().map(snapshot -> {
      var value = new LinkedHashMap<String, Object>();
      value.put("sourceKind", snapshot.sourceKind());
      value.put("sourceCount", snapshot.sourceCount());
      value.put("sourceDigest", snapshot.sourceDigest());
      value.put("relationalDigest", snapshot.relationalDigest());
      return (Object) value;
    }).toList());
  }

  private static boolean digest(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }
}

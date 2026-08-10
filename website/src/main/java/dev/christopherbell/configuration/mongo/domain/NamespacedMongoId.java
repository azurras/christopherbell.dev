package dev.christopherbell.configuration.mongo.domain;

import java.util.List;
import org.bson.Document;

/** Collision-proof physical identity retaining the exact legacy BSON identity value. */
public record NamespacedMongoId(String kind, Object legacyId) {
  public NamespacedMongoId {
    if (!DomainDocumentKind.isCanonicalName(kind) || legacyId == null) {
      throw new IllegalArgumentException("Namespaced Mongo identity is incomplete.");
    }
  }

  public static NamespacedMongoId of(String kind, Object legacyId) {
    return new NamespacedMongoId(kind, legacyId);
  }

  /** Returns the canonical BSON identity with order significant for compound `_id` indexes. */
  public Document toBson() {
    return new Document("kind", kind).append("legacyId", legacyId);
  }

  /** Validates and reads an identity without including stored values in failure messages. */
  public static NamespacedMongoId require(Document id, String expectedKind) {
    if (id == null
        || !List.copyOf(id.keySet()).equals(List.of("kind", "legacyId"))
        || !expectedKind.equals(id.get("kind"))
        || id.get("legacyId") == null) {
      throw new MalformedDomainDocumentException();
    }
    return new NamespacedMongoId(expectedKind, id.get("legacyId"));
  }
}

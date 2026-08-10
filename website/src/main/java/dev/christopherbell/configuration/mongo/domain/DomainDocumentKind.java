package dev.christopherbell.configuration.mongo.domain;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable approval metadata for one logical kind in a consolidated collection. */
public record DomainDocumentKind<T>(
    String collection, String kind, int schemaVersion, Class<T> javaType) {
  private static final Set<String> APPROVED_COLLECTIONS = Set.of(
      "accounts",
      "sessions",
      "communications",
      "content",
      "federation",
      "music",
      "whatsforlunch",
      "shared_folder",
      "vehicles",
      "location",
      "canes_box_tracker",
      "application_runtime",
      "application_migrations",
      "admin_activity");
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");

  public DomainDocumentKind {
    if (!APPROVED_COLLECTIONS.contains(collection) || !isCanonicalName(kind)) {
      throw new IllegalArgumentException("Mongo collection and kind must be canonical.");
    }
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("Mongo schema version must be positive.");
    }
    Objects.requireNonNull(javaType, "javaType");
  }

  static boolean isCanonicalName(String value) {
    return value != null && NAME.matcher(value).matches();
  }
}

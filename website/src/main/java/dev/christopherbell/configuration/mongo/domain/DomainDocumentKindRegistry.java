package dev.christopherbell.configuration.mongo.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable approval boundary associating each logical kind with one target collection. */
public final class DomainDocumentKindRegistry {
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
  private static final String INVALID_APPROVALS =
      "Mongo collection and kind approvals must be canonical.";
  private static final String UNKNOWN_KIND = "Mongo domain kind is not approved.";

  private final Map<String, ApprovedKind> approvedKinds;

  private DomainDocumentKindRegistry(Map<String, ApprovedKind> approvedKinds) {
    this.approvedKinds = Map.copyOf(approvedKinds);
  }

  /** Creates an exact registry from logical-kind keys to approved collection values. */
  public static DomainDocumentKindRegistry of(Map<String, String> approvedCollectionsByKind) {
    Objects.requireNonNull(approvedCollectionsByKind, "approvedCollectionsByKind");
    var approvedKinds = new HashMap<String, ApprovedKind>();
    approvedCollectionsByKind.forEach((kind, collection) -> {
      if (!isCanonicalName(kind) || !APPROVED_COLLECTIONS.contains(collection)) {
        throw new IllegalArgumentException(INVALID_APPROVALS);
      }
      approvedKinds.put(kind, new ApprovedKind(collection, kind));
    });
    return new DomainDocumentKindRegistry(approvedKinds);
  }

  /** Resolves trusted metadata only when the supplied kind is in this exact registry. */
  public <T> DomainDocumentKind<T> require(
      String kind, int schemaVersion, Class<T> javaType) {
    var approvedKind = approvedKinds.get(kind);
    if (approvedKind == null) {
      throw new IllegalArgumentException(UNKNOWN_KIND);
    }
    return DomainDocumentKind.approved(approvedKind, schemaVersion, javaType);
  }

  static boolean isCanonicalName(String value) {
    return value != null && NAME.matcher(value).matches();
  }

  static final class ApprovedKind {
    private final String collection;
    private final String kind;

    private ApprovedKind(String collection, String kind) {
      this.collection = collection;
      this.kind = kind;
    }

    String collection() {
      return collection;
    }

    String kind() {
      return kind;
    }
  }
}

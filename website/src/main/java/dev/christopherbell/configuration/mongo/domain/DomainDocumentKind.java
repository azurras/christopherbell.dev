package dev.christopherbell.configuration.mongo.domain;

import java.util.Objects;

/** Immutable metadata for one logical kind approved by a domain-kind registry. */
public final class DomainDocumentKind<T> {
  private final String collection;
  private final String kind;
  private final int schemaVersion;
  private final Class<T> javaType;

  private DomainDocumentKind(
      DomainDocumentKindRegistry.ApprovedKind approvedKind,
      int schemaVersion,
      Class<T> javaType) {
    Objects.requireNonNull(approvedKind, "approvedKind");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("Mongo schema version must be positive.");
    }
    this.collection = approvedKind.collection();
    this.kind = approvedKind.kind();
    this.schemaVersion = schemaVersion;
    this.javaType = Objects.requireNonNull(javaType, "javaType");
  }

  static <T> DomainDocumentKind<T> approved(
      DomainDocumentKindRegistry.ApprovedKind approvedKind,
      int schemaVersion,
      Class<T> javaType) {
    return new DomainDocumentKind<>(approvedKind, schemaVersion, javaType);
  }

  public String collection() {
    return collection;
  }

  public String kind() {
    return kind;
  }

  public int schemaVersion() {
    return schemaVersion;
  }

  public Class<T> javaType() {
    return javaType;
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof DomainDocumentKind<?> that
        && schemaVersion == that.schemaVersion
        && collection.equals(that.collection)
        && kind.equals(that.kind)
        && javaType.equals(that.javaType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(collection, kind, schemaVersion, javaType);
  }

  @Override
  public String toString() {
    return "DomainDocumentKind[collection=" + collection
        + ", kind=" + kind
        + ", schemaVersion=" + schemaVersion
        + ", javaType=" + javaType + "]";
  }
}

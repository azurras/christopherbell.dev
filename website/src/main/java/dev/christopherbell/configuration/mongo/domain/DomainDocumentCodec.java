package dev.christopherbell.configuration.mongo.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.util.NumberUtils;

/** Converts domain objects at the only boundary where envelope metadata is visible. */
final class DomainDocumentCodec<T> {
  private static final List<String> ENVELOPE_FIELDS =
      List.of("_id", "_kind", "schemaVersion", "payload");
  private static final String INVALID_VALUE = "Mongo domain value is invalid.";

  private final DomainDocumentKind<T> kind;
  private final MongoConverter converter;
  private final MongoPersistentEntity<?> entity;
  private final MongoPersistentProperty versionProperty;

  DomainDocumentCodec(DomainDocumentKind<T> kind, MongoConverter converter) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.entity = new QueryMapper(converter).getMappingContext()
        .getRequiredPersistentEntity(kind.javaType());
    if (!entity.hasIdProperty()) {
      throw invalidValue();
    }
    this.versionProperty = entity.getVersionProperty();
  }

  MongoPersistentEntity<?> entity() {
    return entity;
  }

  MongoPersistentProperty idProperty() {
    return entity.getRequiredIdProperty();
  }

  boolean isVersioned() {
    return versionProperty != null;
  }

  Optional<String> versionFieldName() {
    return Optional.ofNullable(versionProperty).map(MongoPersistentProperty::getFieldName);
  }

  Document encode(T value) {
    if (value == null || !kind.javaType().isInstance(value)) {
      throw invalidValue();
    }
    try {
      var payload = new Document();
      converter.write(value, payload);
      var legacyId = payload.remove("_id");
      if (legacyId == null) {
        throw invalidValue();
      }
      return new Document("_id", NamespacedMongoId.of(kind.kind(), legacyId).toBson())
          .append("_kind", kind.kind())
          .append("schemaVersion", kind.schemaVersion())
          .append("payload", payload);
    } catch (RuntimeException failure) {
      throw invalidValue();
    }
  }

  Document writeDomainDocument(T value) {
    if (value == null || !kind.javaType().isInstance(value)) {
      throw invalidValue();
    }
    try {
      var mapped = new Document();
      converter.write(value, mapped);
      return mapped;
    } catch (RuntimeException failure) {
      throw invalidValue();
    }
  }

  Object mappedIdFromSource(T value) {
    try {
      var sourceId = entity.getPropertyAccessor(value).getProperty(idProperty());
      if (sourceId == null) {
        return null;
      }
      var mapped = new QueryMapper(converter).getMappedObject(
          new Document(idProperty().getName(), sourceId), entity);
      return mapped.get("_id");
    } catch (RuntimeException failure) {
      throw invalidValue();
    }
  }

  Object newStoredId() {
    return new ObjectId();
  }

  T populateIdIfNecessary(T value, Object storedId) {
    try {
      var accessor = entity.getPropertyAccessor(value);
      var idProperty = idProperty();
      if (accessor.getProperty(idProperty) == null) {
        accessor.setProperty(
            idProperty, converter.convertId(storedId, idProperty.getType()));
      }
      return kind.javaType().cast(accessor.getBean());
    } catch (RuntimeException failure) {
      throw invalidValue();
    }
  }

  T populateVersion(T value, Document envelope) {
    if (versionProperty == null) {
      return value;
    }
    try {
      var accessor = entity.getPropertyAccessor(value);
      accessor.setProperty(versionProperty, version(envelope));
      return kind.javaType().cast(accessor.getBean());
    } catch (RuntimeException failure) {
      throw invalidValue();
    }
  }

  Document domainDocument(Document envelope) {
    var identity = NamespacedMongoId.require(
        envelope.get("_id", Document.class), kind.kind());
    var mapped = new Document("_id", identity.legacyId());
    mapped.putAll(envelope.get("payload", Document.class));
    return mapped;
  }

  Document envelopeFromDomainDocument(Document mapped) {
    return envelopeFromDomainDocument(mapped, null);
  }

  Document envelopeFromDomainDocument(Document mapped, Object fallbackLegacyId) {
    try {
      var copy = new Document(mapped);
      var legacyId = copy.remove("_id");
      if (legacyId == null) {
        legacyId = fallbackLegacyId;
      }
      if (legacyId == null) {
        throw invalidValue();
      }
      return new Document("_id", NamespacedMongoId.of(kind.kind(), legacyId).toBson())
          .append("_kind", kind.kind())
          .append("schemaVersion", kind.schemaVersion())
          .append("payload", copy);
    } catch (RuntimeException failure) {
      throw invalidValue();
    }
  }

  T decode(Document envelope) {
    try {
      if (envelope == null
          || !java.util.Set.copyOf(envelope.keySet()).equals(java.util.Set.copyOf(ENVELOPE_FIELDS))) {
        throw new MalformedDomainDocumentException();
      }
      if (!kind.kind().equals(envelope.get("_kind"))
          || !(envelope.get("schemaVersion") instanceof Integer schemaVersion)
          || schemaVersion != kind.schemaVersion()
          || !(envelope.get("_id") instanceof Document bsonId)
          || !(envelope.get("payload") instanceof Document payload)
          || payload.containsKey("_id")) {
        throw new MalformedDomainDocumentException();
      }
      var identity = NamespacedMongoId.require(bsonId, kind.kind());
      var mappedDomain = new Document("_id", identity.legacyId());
      mappedDomain.putAll(payload);
      var value = converter.read(kind.javaType(), mappedDomain);
      verifyIdentityRoundTrip(value, identity.legacyId());
      return value;
    } catch (MalformedDomainDocumentException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new MalformedDomainDocumentException();
    }
  }

  Object version(Document envelope) {
    if (versionProperty == null) {
      return null;
    }
    return envelope.get("payload", Document.class).get(versionProperty.getFieldName());
  }

  Document initializeVersion(Document envelope) {
    if (versionProperty == null || version(envelope) != null) {
      return envelope;
    }
    return withVersion(envelope, zeroForVersionType());
  }

  Document incrementVersion(Document envelope) {
    if (versionProperty == null) {
      return envelope;
    }
    return withVersion(envelope, nextVersion(version(envelope)));
  }

  private void verifyIdentityRoundTrip(T value, Object expectedId) {
    var remapped = new Document();
    converter.write(value, remapped);
    if (!Objects.equals(expectedId, remapped.get("_id"))) {
      throw new MalformedDomainDocumentException();
    }
  }

  private Document withVersion(Document envelope, Number version) {
    var copy = new Document(envelope);
    var payload = new Document(envelope.get("payload", Document.class));
    payload.put(versionProperty.getFieldName(), version);
    copy.put("payload", payload);
    return copy;
  }

  private Number zeroForVersionType() {
    return convertVersion(BigInteger.ZERO);
  }

  private Number nextVersion(Object current) {
    if (!(current instanceof Number number)) {
      throw invalidValue();
    }
    try {
      var integer = new BigDecimal(number.toString()).toBigIntegerExact();
      if (integer.signum() < 0) {
        throw invalidValue();
      }
      return convertVersion(integer.add(BigInteger.ONE));
    } catch (ArithmeticException failure) {
      throw invalidValue();
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private Number convertVersion(BigInteger value) {
    var target = wrap(versionProperty.getType());
    if (!Number.class.isAssignableFrom(target)) {
      throw invalidValue();
    }
    try {
      return NumberUtils.convertNumberToTargetClass(value, (Class) target);
    } catch (IllegalArgumentException failure) {
      throw invalidValue();
    }
  }

  private static Class<?> wrap(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    if (type == byte.class) {
      return Byte.class;
    }
    if (type == short.class) {
      return Short.class;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == long.class) {
      return Long.class;
    }
    return type;
  }

  private static InvalidDataAccessApiUsageException invalidValue() {
    return new InvalidDataAccessApiUsageException(INVALID_VALUE);
  }
}

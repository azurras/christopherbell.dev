package dev.christopherbell.configuration.mongo.domain;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Mongo-backed implementation that owns all access to one approved logical kind. */
public final class MongoKindScopedOperations<T> implements KindScopedMongoOperations<T> {
  private static final String STALE_MESSAGE =
      "Mongo domain document was changed by another writer.";
  private static final String DUPLICATE_MESSAGE = "Mongo domain identity already exists.";

  private final MongoTemplate mongo;
  private final DomainDocumentKind<T> kind;
  private final DomainDocumentCodec<T> codec;
  private final DomainMongoFieldMapper fieldMapper;
  private final QueryMapper idMapper;

  public MongoKindScopedOperations(MongoTemplate mongo, DomainDocumentKind<T> kind) {
    this.mongo = Objects.requireNonNull(mongo, "mongo");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.codec = new DomainDocumentCodec<>(kind, mongo.getConverter());
    this.fieldMapper = new DomainMongoFieldMapper(
        kind.kind(), codec.entity(), mongo.getConverter());
    this.idMapper = new QueryMapper(mongo.getConverter());
  }

  @Override
  public Optional<T> findById(Object legacyId) {
    return Optional.ofNullable(findEnvelopeById(mappedLegacyId(legacyId))).map(codec::decode);
  }

  @Override
  public Optional<T> findOne(Query domainQuery) {
    var envelope = mongo.findOne(
        fieldMapper.mapQuery(domainQuery), Document.class, kind.collection());
    return Optional.ofNullable(envelope).map(codec::decode);
  }

  @Override
  public List<T> find(Query domainQuery, Pageable page) {
    return mongo.find(
            fieldMapper.mapQuery(domainQuery, page), Document.class, kind.collection())
        .stream()
        .map(codec::decode)
        .toList();
  }

  @Override
  public long count(Query domainQuery) {
    return mongo.count(fieldMapper.mapQuery(domainQuery), Document.class, kind.collection());
  }

  @Override
  public boolean exists(Query domainQuery) {
    return mongo.exists(fieldMapper.mapQuery(domainQuery), Document.class, kind.collection());
  }

  @Override
  public T insert(T value) {
    return insertEnvelope(codec.initializeVersion(codec.encode(value)), false);
  }

  @Override
  public T save(T value) {
    var candidate = codec.encode(value);
    var bsonId = candidate.get("_id", Document.class);
    var mappedLegacyId = NamespacedMongoId.require(bsonId, kind.kind()).legacyId();
    var existing = findEnvelopeById(mappedLegacyId);
    if (existing == null) {
      if (codec.isVersioned() && codec.version(candidate) != null) {
        throw stale();
      }
      return insertEnvelope(codec.initializeVersion(candidate), codec.isVersioned());
    }
    codec.decode(existing);
    if (!codec.isVersioned()) {
      return replaceOrInsert(fieldMapper.idQuery(mappedLegacyId), candidate);
    }

    var expectedVersion = codec.version(candidate);
    var replacement = expectedVersion == null
        ? codec.initializeVersion(candidate)
        : codec.incrementVersion(candidate);
    var versionField = codec.versionFieldName().orElseThrow();
    var replaced = replace(
        fieldMapper.versionQuery(mappedLegacyId, versionField, expectedVersion), replacement);
    if (replaced == null) {
      throw stale();
    }
    return codec.decode(replaced);
  }

  @Override
  public UpdateResult updateFirst(Query domainQuery, Update domainUpdate) {
    try {
      return mongo.updateFirst(
          fieldMapper.mapQuery(domainQuery),
          fieldMapper.mapUpdate(domainUpdate),
          Document.class,
          kind.collection());
    } catch (DuplicateKeyException failure) {
      throw duplicate();
    }
  }

  @Override
  public DeleteResult remove(Query domainQuery) {
    return mongo.remove(fieldMapper.mapQuery(domainQuery), Document.class, kind.collection());
  }

  @Override
  public String collectionName() {
    return kind.collection();
  }

  private T replaceOrInsert(Query query, Document candidate) {
    var replaced = replace(query, candidate);
    return replaced == null
        ? insertEnvelope(candidate, false)
        : codec.decode(replaced);
  }

  private Document replace(Query query, Document candidate) {
    try {
      return mongo.findAndReplace(
          query,
          candidate,
          FindAndReplaceOptions.options().returnNew(),
          Document.class,
          kind.collection(),
          Document.class);
    } catch (DuplicateKeyException failure) {
      throw duplicate();
    }
  }

  private T insertEnvelope(Document candidate, boolean contentionIsStale) {
    try {
      return codec.decode(mongo.insert(candidate, kind.collection()));
    } catch (DuplicateKeyException failure) {
      if (contentionIsStale) {
        throw stale();
      }
      throw duplicate();
    }
  }

  private Document findEnvelopeById(Object mappedLegacyId) {
    return mongo.findOne(
        fieldMapper.idQuery(mappedLegacyId), Document.class, kind.collection());
  }

  private Object mappedLegacyId(Object legacyId) {
    if (legacyId == null) {
      throw new UnapprovedDomainFieldException();
    }
    var idProperty = codec.idProperty();
    final Object mappedId;
    try {
      var mapped = idMapper.getMappedObject(
          new Document(idProperty.getName(), legacyId), codec.entity());
      mappedId = mapped.get("_id");
    } catch (RuntimeException failure) {
      throw new UnapprovedDomainFieldException();
    }
    if (mappedId == null) {
      throw new UnapprovedDomainFieldException();
    }
    return mappedId;
  }

  private static OptimisticLockingFailureException stale() {
    return new OptimisticLockingFailureException(STALE_MESSAGE);
  }

  private static DuplicateKeyException duplicate() {
    return new DuplicateKeyException(DUPLICATE_MESSAGE);
  }
}

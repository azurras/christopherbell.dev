package dev.christopherbell.configuration.mongo.domain;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveCallback;

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
  private final EntityCallbacks callbacks;

  public MongoKindScopedOperations(MongoTemplate mongo, DomainDocumentKind<T> kind) {
    this(mongo, kind, EntityCallbacks.create());
  }

  MongoKindScopedOperations(
      MongoTemplate mongo, DomainDocumentKind<T> kind, EntityCallbacks callbacks) {
    this.mongo = Objects.requireNonNull(mongo, "mongo");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
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
    var prepared = prepareForSave(value);
    return insertEnvelope(prepared, codec.initializeVersion(prepared.envelope()), false);
  }

  @Override
  public T save(T value) {
    var prepared = prepareForSave(value);
    var candidate = prepared.envelope();
    var bsonId = candidate.get("_id", Document.class);
    var mappedLegacyId = NamespacedMongoId.require(bsonId, kind.kind()).legacyId();
    var existing = findEnvelopeById(mappedLegacyId);
    if (existing == null) {
      if (codec.isVersioned() && codec.version(candidate) != null) {
        throw stale();
      }
      return insertEnvelope(
          prepared, codec.initializeVersion(candidate), codec.isVersioned());
    }
    codec.decode(existing);
    if (!codec.isVersioned()) {
      return replaceOrInsert(prepared, fieldMapper.idQuery(mappedLegacyId), candidate);
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
    return afterSave(persistedSource(prepared.source(), replaced), replaced);
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
  public Optional<T> findAndUpdate(Query domainQuery, Update domainUpdate) {
    try {
      var envelope = mongo.findAndModify(
          fieldMapper.mapQuery(domainQuery),
          fieldMapper.mapUpdate(domainUpdate),
          FindAndModifyOptions.options().returnNew(true),
          Document.class,
          kind.collection());
      return Optional.ofNullable(envelope).map(codec::decode);
    } catch (DuplicateKeyException failure) {
      throw duplicate();
    }
  }

  @Override
  public T upsertById(Object legacyId, Update domainUpdate) {
    var mappedId = mappedLegacyId(legacyId);
    var mappedUpdate = fieldMapper.mapUpdate(domainUpdate);
    var setOnInsert = mappedUpdate.getUpdateObject().get("$setOnInsert", Document.class);
    if (setOnInsert == null) {
      setOnInsert = new Document();
      mappedUpdate.getUpdateObject().put("$setOnInsert", setOnInsert);
    }
    setOnInsert.put("_id", NamespacedMongoId.of(kind.kind(), mappedId).toBson());
    setOnInsert.put("_kind", kind.kind());
    setOnInsert.put("schemaVersion", kind.schemaVersion());
    try {
      var envelope = mongo.findAndModify(
          fieldMapper.idQuery(mappedId),
          mappedUpdate,
          FindAndModifyOptions.options().upsert(true).returnNew(true),
          Document.class,
          kind.collection());
      if (envelope == null) {
        throw new IllegalStateException("Mongo domain upsert returned no value.");
      }
      return codec.decode(envelope);
    } catch (DuplicateKeyException failure) {
      throw duplicate();
    }
  }

  @Override
  public Optional<T> decrementFloorZeroById(
      Object legacyId,
      String counterField,
      int decrement,
      String timestampField,
      Instant changedOn) {
    if (decrement <= 0 || changedOn == null) {
      throw new UnapprovedDomainFieldException();
    }
    var counterPath = fieldMapper.mapWritablePath(counterField);
    var timestampPath = fieldMapper.mapWritablePath(timestampField);
    var nextCounter = new Document("$max", List.of(
        0,
        new Document("$subtract", List.of(
            new Document("$ifNull", List.of("$" + counterPath, 0)),
            decrement))));
    var update = AggregationUpdate.from(List.of(context ->
        new Document("$set", new Document(counterPath, nextCounter)
            .append(timestampPath, changedOn))));
    var envelope = mongo.findAndModify(
        fieldMapper.idQuery(mappedLegacyId(legacyId)),
        update,
        FindAndModifyOptions.options().returnNew(true),
        Document.class,
        kind.collection());
    return Optional.ofNullable(envelope).map(codec::decode);
  }

  @Override
  public UpdateResult updateMulti(Query domainQuery, Update domainUpdate) {
    try {
      return mongo.updateMulti(
          fieldMapper.mapQuery(domainQuery),
          fieldMapper.mapUpdate(domainUpdate),
          Document.class,
          kind.collection());
    } catch (DuplicateKeyException failure) {
      throw duplicate();
    }
  }

  @Override
  public <R> List<R> aggregate(KindScopedAggregation domainAggregation, Class<R> resultType) {
    Objects.requireNonNull(domainAggregation, "domainAggregation");
    Objects.requireNonNull(resultType, "resultType");
    var operations = new java.util.ArrayList<AggregationOperation>();
    var selector = fieldMapper.mapQuery(
        new org.springframework.data.mongodb.core.query.BasicQuery(
            domainAggregation.trustedSelector())).getQueryObject();
    DomainEnvelopeAggregationValidation.stages(
        selector, kind.kind(), kind.schemaVersion()).stream()
        .map(Document::new)
        .<AggregationOperation>map(stage -> context -> stage)
        .forEach(operations::add);
    operations.add(context -> new Document("$replaceRoot", new Document(
        "newRoot", new Document("$mergeObjects", List.of(
            "$payload", new Document("_id", "$_id.legacyId"))))));
    domainAggregation.pipeline().stream()
        .map(Document::new)
        .<AggregationOperation>map(stage -> context -> stage)
        .forEach(operations::add);
    final List<Document> mapped;
    try {
      mapped = mongo.aggregate(
          Aggregation.newAggregation(operations), kind.collection(), Document.class)
          .getMappedResults();
    } catch (RuntimeException failure) {
      if (DomainEnvelopeAggregationValidation.isControlledFailure(failure)) {
        throw new MalformedDomainDocumentException();
      }
      throw failure;
    }
    if (resultType.equals(kind.javaType())) {
      return mapped.stream()
          .map(codec::envelopeFromDomainDocument)
          .map(codec::decode)
          .map(resultType::cast)
          .toList();
    }
    return mapped.stream().map(document -> mongo.getConverter().read(resultType, document)).toList();
  }

  @Override
  public DeleteResult remove(Query domainQuery) {
    return mongo.remove(fieldMapper.mapQuery(domainQuery), Document.class, kind.collection());
  }

  @Override
  public String collectionName() {
    return kind.collection();
  }

  private T replaceOrInsert(PreparedSave<T> prepared, Query query, Document candidate) {
    var replaced = replace(query, candidate);
    return replaced == null
        ? insertEnvelope(prepared, candidate, false)
        : afterSave(persistedSource(prepared.source(), replaced), replaced);
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

  private T insertEnvelope(
      PreparedSave<T> prepared, Document candidate, boolean contentionIsStale) {
    try {
      var persisted = mongo.insert(candidate, kind.collection());
      return afterSave(persistedSource(prepared.source(), persisted), persisted);
    } catch (DuplicateKeyException failure) {
      if (contentionIsStale) {
        throw stale();
      }
      throw duplicate();
    }
  }

  private PreparedSave<T> prepareForSave(T value) {
    var converted = kind.javaType().cast(
        callbacks.callback(BeforeConvertCallback.class, value, kind.collection()));
    var mapped = codec.writeDomainDocument(converted);
    var callbackSource = kind.javaType().cast(
        callbacks.callback(BeforeSaveCallback.class, converted, mapped, kind.collection()));
    var mappedId = mapped.get("_id");
    if (mappedId == null) {
      mappedId = codec.mappedIdFromSource(callbackSource);
    }
    if (mappedId == null) {
      mappedId = codec.newStoredId();
    }
    return new PreparedSave<>(
        callbackSource, codec.envelopeFromDomainDocument(mapped, mappedId));
  }

  private T afterSave(T value, Document envelope) {
    return kind.javaType().cast(callbacks.callback(
        AfterSaveCallback.class, value, codec.domainDocument(envelope), kind.collection()));
  }

  private T persistedSource(T source, Document envelope) {
    var identity = NamespacedMongoId.require(
        envelope.get("_id", Document.class), kind.kind());
    return codec.populateVersion(
        codec.populateIdIfNecessary(source, identity.legacyId()), envelope);
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

  private record PreparedSave<T>(T source, Document envelope) {}
}

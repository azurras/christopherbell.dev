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
    var candidate = prepareEnvelopeForSave(prepared);
    return insertEnvelope(codec.initializeVersion(candidate), false);
  }

  @Override
  public T save(T value) {
    var prepared = prepareForSave(value);
    var candidate = prepareEnvelopeForSave(prepared);
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
    return afterSave(codec.decode(replaced), replaced);
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
    rejectMalformedStoredEnvelopes();
    var operations = new java.util.ArrayList<AggregationOperation>();
    operations.add(context -> new Document("$match", new Document("_kind", kind.kind())
        .append("schemaVersion", kind.schemaVersion())
        .append("_id.kind", kind.kind())
        .append("_id.legacyId", new Document("$exists", true))
        .append("payload", new Document("$type", "object"))));
    operations.add(context -> new Document("$replaceRoot", new Document(
        "newRoot", new Document("$mergeObjects", List.of(
            "$payload", new Document("_id", "$_id.legacyId"))))));
    domainAggregation.pipeline().stream()
        .map(Document::new)
        .<AggregationOperation>map(stage -> context -> stage)
        .forEach(operations::add);
    var mapped = mongo.aggregate(
        Aggregation.newAggregation(operations), kind.collection(), Document.class)
        .getMappedResults();
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

  private T replaceOrInsert(Query query, Document candidate) {
    var replaced = replace(query, candidate);
    return replaced == null
        ? insertEnvelope(candidate, false)
        : afterSave(codec.decode(replaced), replaced);
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
      return afterSave(codec.decode(mongo.insert(candidate, kind.collection())), candidate);
    } catch (DuplicateKeyException failure) {
      if (contentionIsStale) {
        throw stale();
      }
      throw duplicate();
    }
  }

  private T prepareForSave(T value) {
    var audited = callbacks.callback(BeforeConvertCallback.class, value, kind.collection());
    return codec.populateIdIfNecessary(kind.javaType().cast(audited));
  }

  private Document prepareEnvelopeForSave(T value) {
    var envelope = codec.encode(value);
    var mapped = codec.domainDocument(envelope);
    callbacks.callback(BeforeSaveCallback.class, value, mapped, kind.collection());
    return codec.envelopeFromDomainDocument(mapped);
  }

  private T afterSave(T value, Document envelope) {
    return kind.javaType().cast(callbacks.callback(
        AfterSaveCallback.class, value, codec.domainDocument(envelope), kind.collection()));
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

  private void rejectMalformedStoredEnvelopes() {
    var malformed = new Document("$and", List.of(
        new Document("_kind", kind.kind()),
        new Document("$or", List.of(
            new Document("schemaVersion", new Document("$ne", kind.schemaVersion())),
            new Document("_id.kind", new Document("$ne", kind.kind())),
            new Document("_id.legacyId", new Document("$exists", false)),
            new Document("payload", new Document("$not", new Document("$type", "object"))),
            new Document("payload._id", new Document("$exists", true)),
            unexpectedKeys("$$ROOT", List.of("_id", "_kind", "schemaVersion", "payload")),
            unexpectedKeys("$_id", List.of("kind", "legacyId"))))));
    if (mongo.exists(new org.springframework.data.mongodb.core.query.BasicQuery(malformed),
        Document.class, kind.collection())) {
      throw new MalformedDomainDocumentException();
    }
  }

  private static Document unexpectedKeys(String input, List<String> approvedKeys) {
    var keys = new Document("$map", new Document("input", new Document("$objectToArray", input))
        .append("as", "field")
        .append("in", "$$field.k"));
    return new Document("$expr", new Document("$not", List.of(
        new Document("$setEquals", List.of(keys, approvedKeys)))));
  }

  private static OptimisticLockingFailureException stale() {
    return new OptimisticLockingFailureException(STALE_MESSAGE);
  }

  private static DuplicateKeyException duplicate() {
    return new DuplicateKeyException(DUPLICATE_MESSAGE);
  }
}

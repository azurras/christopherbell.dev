package dev.christopherbell.configuration.mongo.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.convert.QueryMapper;
import org.springframework.data.mongodb.core.convert.UpdateMapper;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.data.mongodb.core.query.BasicUpdate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

/** Maps approved domain paths while making envelope paths impossible to request directly. */
final class DomainMongoFieldMapper {
  private static final Set<String> LOGICAL_QUERY_OPERATORS = Set.of("$and", "$or", "$nor");
  private static final Set<String> UNSAFE_QUERY_OPERATORS =
      Set.of("$expr", "$where", "$jsonSchema");
  private static final Set<String> UPDATE_OPERATORS = Set.of(
      "$set",
      "$setOnInsert",
      "$unset",
      "$inc",
      "$mul",
      "$min",
      "$max",
      "$currentDate",
      "$push",
      "$addToSet",
      "$pop",
      "$pull",
      "$pullAll",
      "$bit",
      "$rename");

  private final String kind;
  private final MongoPersistentEntity<?> entity;
  private final MongoPersistentProperty versionProperty;
  private final QueryMapper queryMapper;
  private final UpdateMapper updateMapper;

  DomainMongoFieldMapper(
      String kind, MongoPersistentEntity<?> entity, MongoConverter converter) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.entity = Objects.requireNonNull(entity, "entity");
    this.versionProperty = entity.getVersionProperty();
    this.queryMapper = new QueryMapper(Objects.requireNonNull(converter, "converter"));
    this.updateMapper = new UpdateMapper(converter);
  }

  Query mapQuery(Query domainQuery) {
    return mapQuery(domainQuery, null);
  }

  Query mapQuery(Query domainQuery, Pageable page) {
    if (domainQuery == null || !domainQuery.getFieldsObject().isEmpty()
        || !domainQuery.getRestrictedTypes().isEmpty()) {
      throw new UnapprovedDomainFieldException();
    }
    var source = Query.of(domainQuery);
    if (page != null) {
      Objects.requireNonNull(page, "page");
      if (page.isPaged()) {
        source.with(page);
      } else if (page.getSort().isSorted()) {
        source.with(page.getSort());
      }
    }
    validateQueryDocument(source.getQueryObject());
    validateSort(source.getSortObject());
    try {
      var mapped = queryMapper.getMappedObject(source.getQueryObject(), entity);
      var scoped = scope(namespaceQuery(mapped));
      var result = new BasicQuery(scoped);
      result.setSortObject(namespaceSort(queryMapper.getMappedSort(source.getSortObject(), entity)));
      copySettings(source, result);
      return result;
    } catch (UnapprovedDomainFieldException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new UnapprovedDomainFieldException();
    }
  }

  Query mapMutationQuery(Query domainQuery, int schemaVersion) {
    return guardMutation(mapQuery(domainQuery), schemaVersion);
  }

  Query guardMutation(Query mappedQuery, int schemaVersion) {
    var result = new BasicQuery(DomainEnvelopeAggregationValidation.mutationSelector(
        mappedQuery.getQueryObject(), kind, schemaVersion));
    result.setSortObject(new Document(mappedQuery.getSortObject()));
    copySettings(mappedQuery, result);
    return result;
  }

  Update mapUpdate(Update domainUpdate) {
    return mapUpdate(domainUpdate, true);
  }

  Update mapHeartbeatPreservingVersion(Update heartbeatUpdate) {
    return mapUpdate(heartbeatUpdate, false);
  }

  Update mapLeaseUpdate(Update leaseUpdate, boolean advanceVersion) {
    return mapUpdate(leaseUpdate, advanceVersion);
  }

  private Update mapUpdate(Update domainUpdate, boolean advanceVersion) {
    if (domainUpdate == null) {
      throw new UnapprovedDomainFieldException();
    }
    validateUpdate(domainUpdate.getUpdateObject());
    try {
      var mapped = updateMapper.getMappedObject(domainUpdate.getUpdateObject(), entity);
      var namespaced = namespaceUpdate(mapped);
      if (advanceVersion) {
        incrementVersion(namespaced);
      }
      return new MappedDomainUpdate(namespaced, domainUpdate.getArrayFilters());
    } catch (UnapprovedDomainFieldException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new UnapprovedDomainFieldException();
    }
  }

  String mapWritablePath(String domainPath) {
    validateDomainPath(domainPath, false, false);
    try {
      var mapped = queryMapper.getMappedObject(new Document(domainPath, 1), entity);
      if (mapped.size() != 1) {
        throw new UnapprovedDomainFieldException();
      }
      return namespacePath(mapped.keySet().iterator().next());
    } catch (UnapprovedDomainFieldException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new UnapprovedDomainFieldException();
    }
  }

  Query idQuery(Object mappedLegacyId) {
    if (mappedLegacyId == null) {
      throw new UnapprovedDomainFieldException();
    }
    return new BasicQuery(new Document("$and", List.of(
        new Document("_kind", kind),
        new Document("_id", NamespacedMongoId.of(kind, mappedLegacyId).toBson()))));
  }

  Query versionQuery(Object mappedLegacyId, String versionField, Object expectedVersion) {
    var versionCriterion = expectedVersion == null
        ? new Document("payload." + versionField, new Document("$exists", false))
        : new Document("payload." + versionField, expectedVersion);
    return new BasicQuery(new Document("$and", List.of(
        new Document("_kind", kind),
        new Document("_id", NamespacedMongoId.of(kind, mappedLegacyId).toBson()),
        versionCriterion)));
  }

  private Document scope(Document mapped) {
    return mapped.isEmpty()
        ? new Document("_kind", kind)
        : new Document("$and", List.of(new Document("_kind", kind), mapped));
  }

  private void validateQueryDocument(Document query) {
    rejectUnsafeOperators(query);
    for (var entry : query.entrySet()) {
      if (LOGICAL_QUERY_OPERATORS.contains(entry.getKey())) {
        if (!(entry.getValue() instanceof List<?> branches)) {
          throw new UnapprovedDomainFieldException();
        }
        for (var branch : branches) {
          if (!(branch instanceof Document document)) {
            throw new UnapprovedDomainFieldException();
          }
          validateQueryDocument(document);
        }
      } else if (entry.getKey().startsWith("$")) {
        throw new UnapprovedDomainFieldException();
      } else {
        validateDomainPath(entry.getKey(), true, true);
      }
    }
  }

  private void rejectUnsafeOperators(Object value) {
    if (value instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        if (UNSAFE_QUERY_OPERATORS.contains(entry.getKey())) {
          throw new UnapprovedDomainFieldException();
        }
        rejectUnsafeOperators(entry.getValue());
      }
    } else if (value instanceof Iterable<?> values) {
      values.forEach(this::rejectUnsafeOperators);
    }
  }

  private void validateSort(Document sort) {
    sort.keySet().forEach(path -> validateDomainPath(path, true, true));
  }

  private void validateUpdate(Document update) {
    if (update.isEmpty()) {
      throw new UnapprovedDomainFieldException();
    }
    for (var operation : update.entrySet()) {
      if (!UPDATE_OPERATORS.contains(operation.getKey())
          || !(operation.getValue() instanceof Document fields)
          || fields.isEmpty()) {
        throw new UnapprovedDomainFieldException();
      }
      for (var field : fields.entrySet()) {
        validateDomainPath(field.getKey(), false, false);
        if ("$rename".equals(operation.getKey())) {
          if (!(field.getValue() instanceof String target)) {
            throw new UnapprovedDomainFieldException();
          }
          validateDomainPath(target, false, false);
        }
      }
    }
  }

  private void validateDomainPath(String path, boolean allowId, boolean allowVersion) {
    if (path == null || path.isBlank() || path.startsWith("$")
        || path.startsWith("payload.") || path.startsWith("_id.")) {
      throw new UnapprovedDomainFieldException();
    }
    var separator = path.indexOf('.');
    var propertyName = separator < 0 ? path : path.substring(0, separator);
    var property = entity.getPersistentProperty(propertyName);
    if (property == null
        || (!allowId && property.isIdProperty())
        || (!allowVersion && property.isVersionProperty())) {
      throw new UnapprovedDomainFieldException();
    }
  }

  private Document namespaceQuery(Document query) {
    var result = new Document();
    for (var entry : query.entrySet()) {
      if (LOGICAL_QUERY_OPERATORS.contains(entry.getKey())) {
        var branches = new ArrayList<Document>();
        for (var branch : (List<?>) entry.getValue()) {
          branches.add(namespaceQuery((Document) branch));
        }
        result.put(entry.getKey(), branches);
      } else if (entry.getKey().startsWith("$")) {
        throw new UnapprovedDomainFieldException();
      } else {
        result.put(namespacePath(entry.getKey()), entry.getValue());
      }
    }
    return result;
  }

  private Document namespaceSort(Document sort) {
    var result = new Document();
    sort.forEach((path, direction) -> result.put(namespacePath(path), direction));
    return result;
  }

  private Document namespaceUpdate(Document update) {
    var result = new Document();
    for (var operation : update.entrySet()) {
      var fields = (Document) operation.getValue();
      var namespacedFields = new Document();
      for (var field : fields.entrySet()) {
        var value = field.getValue();
        if ("$rename".equals(operation.getKey())) {
          value = namespacePath((String) value);
        }
        namespacedFields.put(namespacePath(field.getKey()), value);
      }
      result.put(operation.getKey(), namespacedFields);
    }
    return result;
  }

  private void incrementVersion(Document update) {
    if (versionProperty == null) {
      return;
    }
    var increments = update.get("$inc", Document.class);
    if (increments == null) {
      increments = new Document();
      update.put("$inc", increments);
    }
    increments.put("payload." + versionProperty.getFieldName(), 1);
  }

  private static String namespacePath(String mappedPath) {
    if ("_id".equals(mappedPath)) {
      return "_id.legacyId";
    }
    if (mappedPath.startsWith("_id.")) {
      return "_id.legacyId" + mappedPath.substring("_id".length());
    }
    return "payload." + mappedPath;
  }

  private static void copySettings(Query source, BasicQuery target) {
    target.skip(source.getSkip());
    if (source.isLimited()) {
      target.limit(source.getLimit());
    }
    source.getCollation().ifPresent(target::collation);
    if (source.hasReadConcern()) {
      target.withReadConcern(source.getReadConcern());
    }
    if (source.hasReadPreference()) {
      target.withReadPreference(source.getReadPreference());
    }
    if (source.getHint() != null) {
      target.withHint(source.getHint());
    }
    target.setMeta(source.getMeta());
  }

  private static final class MappedDomainUpdate extends BasicUpdate {
    private final List<UpdateDefinition.ArrayFilter> arrayFilters;

    MappedDomainUpdate(
        Document update, List<UpdateDefinition.ArrayFilter> arrayFilters) {
      super(update);
      this.arrayFilters = List.copyOf(arrayFilters);
    }

    @Override
    public List<UpdateDefinition.ArrayFilter> getArrayFilters() {
      return arrayFilters;
    }

    @Override
    public boolean hasArrayFilters() {
      return !arrayFilters.isEmpty();
    }
  }
}

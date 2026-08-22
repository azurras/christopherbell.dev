package dev.christopherbell.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.repository.Repository;

/** Compiled dependency rule for the final manifest-backed Mongo boundary. */
final class MongoPersistenceBoundaryRules {
  private static final String SPRING_MONGO_ROOT = "org.springframework.data.mongodb.";
  private static final String SPRING_CORE_ROOT = SPRING_MONGO_ROOT + "core.";
  private static final Set<String> INERT_DRIVER_VALUE_PACKAGES = Set.of(
      "com.mongodb.client.cursor.",
      "com.mongodb.client.gridfs.codecs.",
      "com.mongodb.client.gridfs.model.",
      "com.mongodb.client.model.",
      "com.mongodb.client.result.");
  private static final Set<String> INERT_DRIVER_VALUE_TYPES = Set.of(
      "com.mongodb.AutoEncryptionSettings",
      "com.mongodb.ClientEncryptionSettings",
      "com.mongodb.ClientSessionOptions",
      "com.mongodb.MongoClientSettings");
  private static final Set<String> INERT_SPRING_VALUE_PACKAGES = Set.of(
      SPRING_CORE_ROOT + "aggregation.",
      SPRING_CORE_ROOT + "geo.",
      SPRING_CORE_ROOT + "mapping.",
      SPRING_CORE_ROOT + "mapreduce.",
      SPRING_CORE_ROOT + "query.",
      SPRING_CORE_ROOT + "schema.",
      SPRING_CORE_ROOT + "time.",
      SPRING_CORE_ROOT + "validation.");
  private static final Set<String> INERT_SPRING_VALUE_TYPES = Set.of(
      SPRING_MONGO_ROOT + "BindableMongoExpression",
      SPRING_MONGO_ROOT + "BulkOperationException",
      SPRING_MONGO_ROOT + "ClientSessionException",
      SPRING_MONGO_ROOT + "CodecRegistryProvider",
      SPRING_MONGO_ROOT + "DefaultMongoTransactionOptionsResolver",
      SPRING_MONGO_ROOT + "InvalidMongoDbApiUsageException",
      SPRING_MONGO_ROOT + "LazyLoadingException",
      SPRING_MONGO_ROOT + "MongoCollectionUtils",
      SPRING_MONGO_ROOT + "MongoExpression",
      SPRING_MONGO_ROOT + "MongoManagedTypes",
      SPRING_MONGO_ROOT + "MongoTransactionException",
      SPRING_MONGO_ROOT + "MongoTransactionOptions",
      SPRING_MONGO_ROOT + "MongoTransactionOptionsResolver",
      SPRING_MONGO_ROOT + "SessionSynchronization",
      SPRING_MONGO_ROOT + "SimpleMongoTransactionOptions",
      SPRING_MONGO_ROOT + "SpringDataMongoDB",
      SPRING_MONGO_ROOT + "TransactionMetadata",
      SPRING_MONGO_ROOT + "TransactionOptionResolver",
      SPRING_MONGO_ROOT + "TransientClientSessionException",
      SPRING_MONGO_ROOT + "TransientMongoDbException",
      SPRING_MONGO_ROOT + "UncategorizedMongoDbException",
      SPRING_CORE_ROOT + "AggregationUtil",
      SPRING_CORE_ROOT + "ChangeStreamEvent",
      SPRING_CORE_ROOT + "ChangeStreamOptions",
      SPRING_CORE_ROOT + "CollectionOptions",
      SPRING_CORE_ROOT + "CountQuery",
      SPRING_CORE_ROOT + "DefaultWriteConcernResolver",
      SPRING_CORE_ROOT + "DocumentCallbackHandler",
      SPRING_CORE_ROOT + "EncryptionAlgorithms",
      SPRING_CORE_ROOT + "EntityLifecycleEventDelegate",
      SPRING_CORE_ROOT + "EntityOperations",
      SPRING_CORE_ROOT + "EntityResultConverter",
      SPRING_CORE_ROOT + "FindAndModifyOptions",
      SPRING_CORE_ROOT + "FindAndReplaceOptions",
      SPRING_CORE_ROOT + "GeoCommandStatistics",
      SPRING_CORE_ROOT + "HintFunction",
      SPRING_CORE_ROOT + "IndexConverters",
      SPRING_CORE_ROOT + "MappedDocument",
      SPRING_CORE_ROOT + "MappingMongoJsonSchemaCreator",
      SPRING_CORE_ROOT + "MongoAction",
      SPRING_CORE_ROOT + "MongoActionOperation",
      SPRING_CORE_ROOT + "MongoDataIntegrityViolationException",
      SPRING_CORE_ROOT + "MongoExceptionTranslator",
      SPRING_CORE_ROOT + "MongoJsonSchemaCreator",
      SPRING_CORE_ROOT + "PropertyOperations",
      SPRING_CORE_ROOT + "QueryOperations",
      SPRING_CORE_ROOT + "QueryResultConverter",
      SPRING_CORE_ROOT + "ReadConcernAware",
      SPRING_CORE_ROOT + "ReadPreferenceAware",
      SPRING_CORE_ROOT + "ReplaceOptions",
      SPRING_CORE_ROOT + "ScrollUtils",
      SPRING_CORE_ROOT + "SourceAwareDocument",
      SPRING_CORE_ROOT + "ViewOptions",
      SPRING_CORE_ROOT + "WriteConcernAware",
      SPRING_CORE_ROOT + "WriteConcernResolver",
      SPRING_CORE_ROOT + "WriteResultChecking",
      SPRING_MONGO_ROOT + "config.EnableMongoAuditing",
      SPRING_CORE_ROOT + "convert.encryption.ExplicitEncryptionContext",
      SPRING_CORE_ROOT + "encryption.Encryption",
      SPRING_CORE_ROOT + "encryption.EncryptionContext",
      SPRING_CORE_ROOT + "encryption.EncryptionKey",
      SPRING_CORE_ROOT + "encryption.EncryptionKeyResolver",
      SPRING_CORE_ROOT + "encryption.EncryptionOptions",
      SPRING_CORE_ROOT + "encryption.KeyAltName",
      SPRING_CORE_ROOT + "encryption.KeyId",
      SPRING_CORE_ROOT + "index.CompoundIndex",
      SPRING_CORE_ROOT + "index.CompoundIndexDefinition",
      SPRING_CORE_ROOT + "index.CompoundIndexes",
      SPRING_CORE_ROOT + "index.GeospatialIndex",
      SPRING_CORE_ROOT + "index.GeoSpatialIndexed",
      SPRING_CORE_ROOT + "index.GeoSpatialIndexType",
      SPRING_CORE_ROOT + "index.HashedIndex",
      SPRING_CORE_ROOT + "index.HashIndexed",
      SPRING_CORE_ROOT + "index.Index",
      SPRING_CORE_ROOT + "index.IndexDefinition",
      SPRING_CORE_ROOT + "index.IndexDirection",
      SPRING_CORE_ROOT + "index.Indexed",
      SPRING_CORE_ROOT + "index.IndexField",
      SPRING_CORE_ROOT + "index.IndexFilter",
      SPRING_CORE_ROOT + "index.IndexInfo",
      SPRING_CORE_ROOT + "index.IndexOptions",
      SPRING_CORE_ROOT + "index.IndexPredicate",
      SPRING_CORE_ROOT + "index.IndexResolver",
      SPRING_CORE_ROOT + "index.MongoPersistentEntityIndexResolver",
      SPRING_CORE_ROOT + "index.PartialIndexFilter",
      SPRING_CORE_ROOT + "index.SearchIndexDefinition",
      SPRING_CORE_ROOT + "index.SearchIndexInfo",
      SPRING_CORE_ROOT + "index.SearchIndexStatus",
      SPRING_CORE_ROOT + "index.TextIndexDefinition",
      SPRING_CORE_ROOT + "index.TextIndexed",
      SPRING_CORE_ROOT + "index.VectorIndex",
      SPRING_CORE_ROOT + "index.WildcardIndex",
      SPRING_CORE_ROOT + "index.WildcardIndexed",
      SPRING_CORE_ROOT + "messaging.ChangeStreamRequest",
      SPRING_CORE_ROOT + "messaging.LazyMappingDelegatingMessage",
      SPRING_CORE_ROOT + "messaging.Message",
      SPRING_CORE_ROOT + "messaging.MessageListener",
      SPRING_CORE_ROOT + "messaging.SimpleMessage",
      SPRING_CORE_ROOT + "messaging.SubscriptionRequest",
      SPRING_CORE_ROOT + "messaging.TailableCursorRequest",
      SPRING_MONGO_ROOT + "gridfs.AntPath",
      SPRING_MONGO_ROOT + "gridfs.GridFsCriteria");
  private final String rootPackage;
  private final Set<String> approvedOwners;

  MongoPersistenceBoundaryRules(String rootPackage, Set<String> approvedOwners) {
    this.rootPackage = rootPackage;
    this.approvedOwners = Set.copyOf(approvedOwners);
  }

  ArchRule zeroBypassRule() {
    return classes()
        .that().resideInAPackage(rootPackage + "..")
        .should(new NoDirectMongoDependencyCondition());
  }

  private final class NoDirectMongoDependencyCondition extends ArchCondition<JavaClass> {
    private NoDirectMongoDependencyCondition() {
      super("depend on Mongo only through an approved kind-scoped infrastructure owner");
    }

    @Override
    public void check(JavaClass source, ConditionEvents events) {
      if (approvedOwners.contains(source.getName())) {
        return;
      }
      var violations = new TreeSet<String>();
      source.getDirectDependenciesFromSelf().stream()
          .map(dependency -> dependency.getTargetClass())
          .filter(MongoPersistenceBoundaryRules::isForbiddenMongoType)
          .map(JavaClass::getName)
          .forEach(violations::add);
      violations.forEach(target -> events.add(SimpleConditionEvent.violated(
          source, source.getName() + " directly depends on " + target)));
    }
  }

  static boolean isForbiddenMongoType(JavaClass target) {
    if (target.getName().startsWith(SPRING_MONGO_ROOT)
        && target.isAssignableTo(Repository.class)) {
      return true;
    }
    var name = target.getName();
    return isDriverAccessType(name) || isSpringAccessType(name);
  }

  private static boolean isDriverAccessType(String name) {
    if (INERT_DRIVER_VALUE_TYPES.contains(name)
        || startsWithAny(name, INERT_DRIVER_VALUE_PACKAGES)) {
      return false;
    }
    return name.startsWith("com.mongodb.client.")
        || name.startsWith("com.mongodb.internal.")
        || name.startsWith("com.mongodb.session.");
  }

  private static boolean isSpringAccessType(String name) {
    if (INERT_SPRING_VALUE_TYPES.contains(name)
        || startsWithAny(name, INERT_SPRING_VALUE_PACKAGES)) {
      return false;
    }
    var simpleName = name.substring(name.lastIndexOf('.') + 1);
    if (isImmediateChild(name, SPRING_MONGO_ROOT)
        || isImmediateChild(name, SPRING_CORE_ROOT)) {
      return true;
    }
    if (name.startsWith(SPRING_CORE_ROOT + "convert.encryption.")) {
      return true;
    }
    if (name.startsWith(SPRING_CORE_ROOT + "convert.")) {
      return simpleName.contains("DbRef")
          || simpleName.contains("ReferenceLoader")
          || simpleName.contains("ReferenceResolver")
          || simpleName.contains("ReferenceLookupDelegate");
    }
    if (startsWithAny(name, Set.of(
        SPRING_MONGO_ROOT + "config.",
        SPRING_CORE_ROOT + "encryption.",
        SPRING_CORE_ROOT + "index.",
        SPRING_CORE_ROOT + "messaging.",
        SPRING_MONGO_ROOT + "gridfs."))) {
      return true;
    }
    var repositoryRoot = SPRING_MONGO_ROOT + "repository.";
    if (name.startsWith(repositoryRoot)) {
      return name.substring(repositoryRoot.length()).contains(".");
    }
    return false;
  }

  private static boolean startsWithAny(String name, Set<String> prefixes) {
    return prefixes.stream().anyMatch(name::startsWith);
  }

  private static boolean isImmediateChild(String name, String root) {
    return name.startsWith(root) && !name.substring(root.length()).contains(".");
  }
}

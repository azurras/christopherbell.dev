package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoPersistenceBoundaryRulesTest {
  private static final String FIXTURE_ROOT =
      "dev.christopherbell.architecture.fixture.ops.mongo";
  private static final String ACCESS_FIXTURE = FIXTURE_ROOT + ".ForbiddenMongoApiDependencies";
  private static final Set<String> AUDITED_MONGO_ACCESS_TYPES = Set.copyOf("""
      com.mongodb.client.MongoClient
      com.mongodb.client.MongoClientFactory
      com.mongodb.client.MongoClients
      com.mongodb.client.ChangeStreamIterable
      com.mongodb.client.ClientSession
      com.mongodb.client.ListCollectionsIterable
      com.mongodb.client.MapReduceIterable
      com.mongodb.client.MongoChangeStreamCursor
      com.mongodb.client.MongoCluster
      com.mongodb.client.MongoCollection
      com.mongodb.client.MongoCursor
      com.mongodb.client.MongoDatabase
      com.mongodb.client.MongoIterable
      com.mongodb.client.gridfs.GridFSBucket
      com.mongodb.client.gridfs.GridFSBuckets
      com.mongodb.client.gridfs.GridFSDownloadStream
      com.mongodb.client.gridfs.GridFSFindIterable
      com.mongodb.client.gridfs.GridFSUploadStream
      com.mongodb.client.vault.ClientEncryption
      com.mongodb.client.vault.ClientEncryptions
      com.mongodb.internal.binding.ReadBinding
      com.mongodb.internal.connection.Cluster
      com.mongodb.internal.operation.ReadOperation
      com.mongodb.session.ClientSession
      org.springframework.data.mongodb.MongoClusterCapable
      org.springframework.data.mongodb.MongoDatabaseFactory
      org.springframework.data.mongodb.MongoDatabaseUtils
      org.springframework.data.mongodb.MongoSessionProvider
      org.springframework.data.mongodb.MongoTransactionManager
      org.springframework.data.mongodb.ReactiveMongoClusterCapable
      org.springframework.data.mongodb.ReactiveMongoDatabaseFactory
      org.springframework.data.mongodb.ReactiveMongoDatabaseUtils
      org.springframework.data.mongodb.ReactiveMongoTransactionManager
      org.springframework.data.mongodb.SessionAwareMethodInterceptor
      org.springframework.data.mongodb.core.BulkOperations
      org.springframework.data.mongodb.core.DefaultIndexOperations
      org.springframework.data.mongodb.core.DefaultReactiveIndexOperations
      org.springframework.data.mongodb.core.DbCallback
      org.springframework.data.mongodb.core.ExecutableAggregationOperation
      org.springframework.data.mongodb.core.ExecutableFindOperation
      org.springframework.data.mongodb.core.ExecutableInsertOperation
      org.springframework.data.mongodb.core.ExecutableMapReduceOperation
      org.springframework.data.mongodb.core.ExecutableRemoveOperation
      org.springframework.data.mongodb.core.ExecutableUpdateOperation
      org.springframework.data.mongodb.core.FluentMongoOperations
      org.springframework.data.mongodb.core.MongoAdminOperations
      org.springframework.data.mongodb.core.MongoClientFactoryBean
      org.springframework.data.mongodb.core.MongoOperations
      org.springframework.data.mongodb.core.MongoTemplate
      org.springframework.data.mongodb.core.ReactiveAggregationOperation
      org.springframework.data.mongodb.core.ReactiveBulkOperations
      org.springframework.data.mongodb.core.ReactiveChangeStreamOperation
      org.springframework.data.mongodb.core.ReactiveFindOperation
      org.springframework.data.mongodb.core.ReactiveFluentMongoOperations
      org.springframework.data.mongodb.core.ReactiveInsertOperation
      org.springframework.data.mongodb.core.ReactiveMapReduceOperation
      org.springframework.data.mongodb.core.ReactiveMongoClientFactoryBean
      org.springframework.data.mongodb.core.ReactiveMongoOperations
      org.springframework.data.mongodb.core.ReactiveMongoTemplate
      org.springframework.data.mongodb.core.ReactiveRemoveOperation
      org.springframework.data.mongodb.core.ReactiveUpdateOperation
      org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
      org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory
      org.springframework.data.mongodb.core.ScriptOperations
      org.springframework.data.mongodb.core.convert.DbRefResolver
      org.springframework.data.mongodb.core.convert.DefaultDbRefResolver
      org.springframework.data.mongodb.core.convert.DefaultReferenceResolver
      org.springframework.data.mongodb.core.convert.MongoDatabaseFactoryReferenceLoader
      org.springframework.data.mongodb.core.convert.ReferenceLoader
      org.springframework.data.mongodb.core.convert.ReferenceLookupDelegate
      org.springframework.data.mongodb.core.convert.encryption.EncryptingConverter
      org.springframework.data.mongodb.core.convert.encryption.MongoEncryptionConverter
      org.springframework.data.mongodb.core.encryption.MongoClientEncryption
      org.springframework.data.mongodb.core.index.DefaultSearchIndexOperations
      org.springframework.data.mongodb.core.index.IndexOperations
      org.springframework.data.mongodb.core.index.ReactiveIndexOperations
      org.springframework.data.mongodb.core.index.SearchIndexOperations
      org.springframework.data.mongodb.gridfs.GridFsOperations
      org.springframework.data.mongodb.gridfs.GridFsResource
      org.springframework.data.mongodb.gridfs.GridFsTemplate
      org.springframework.data.mongodb.gridfs.GridFsUpload
      org.springframework.data.mongodb.gridfs.ReactiveGridFsOperations
      org.springframework.data.mongodb.gridfs.ReactiveGridFsResource
      org.springframework.data.mongodb.gridfs.ReactiveGridFsTemplate
      org.springframework.data.mongodb.gridfs.ReactiveGridFsUpload
      org.springframework.data.mongodb.config.AbstractMongoClientConfiguration
      org.springframework.data.mongodb.repository.query.AbstractMongoQuery
      org.springframework.data.mongodb.repository.support.MongoRepositoryFactory
      org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean
      org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactory
      org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactoryBean
      """.lines().filter(line -> !line.isBlank()).map(String::strip).toList());
  private static final Set<String> AUDITED_INERT_ACCESS_CANDIDATES = Set.copyOf("""
      org.springframework.data.mongodb.BindableMongoExpression
      org.springframework.data.mongodb.BulkOperationException
      org.springframework.data.mongodb.ClientSessionException
      org.springframework.data.mongodb.CodecRegistryProvider
      org.springframework.data.mongodb.DefaultMongoTransactionOptionsResolver
      org.springframework.data.mongodb.InvalidMongoDbApiUsageException
      org.springframework.data.mongodb.LazyLoadingException
      org.springframework.data.mongodb.MongoCollectionUtils
      org.springframework.data.mongodb.MongoExpression
      org.springframework.data.mongodb.MongoManagedTypes
      org.springframework.data.mongodb.MongoTransactionException
      org.springframework.data.mongodb.MongoTransactionOptions
      org.springframework.data.mongodb.MongoTransactionOptionsResolver
      org.springframework.data.mongodb.SessionSynchronization
      org.springframework.data.mongodb.SimpleMongoTransactionOptions
      org.springframework.data.mongodb.SpringDataMongoDB
      org.springframework.data.mongodb.TransactionMetadata
      org.springframework.data.mongodb.TransactionOptionResolver
      org.springframework.data.mongodb.TransientClientSessionException
      org.springframework.data.mongodb.TransientMongoDbException
      org.springframework.data.mongodb.UncategorizedMongoDbException
      org.springframework.data.mongodb.core.AggregationUtil
      org.springframework.data.mongodb.core.ChangeStreamEvent
      org.springframework.data.mongodb.core.ChangeStreamOptions
      org.springframework.data.mongodb.core.CollectionOptions
      org.springframework.data.mongodb.core.CountQuery
      org.springframework.data.mongodb.core.DefaultWriteConcernResolver
      org.springframework.data.mongodb.core.DocumentCallbackHandler
      org.springframework.data.mongodb.core.EncryptionAlgorithms
      org.springframework.data.mongodb.core.EntityLifecycleEventDelegate
      org.springframework.data.mongodb.core.EntityOperations
      org.springframework.data.mongodb.core.EntityResultConverter
      org.springframework.data.mongodb.core.FindAndModifyOptions
      org.springframework.data.mongodb.core.FindAndReplaceOptions
      org.springframework.data.mongodb.core.GeoCommandStatistics
      org.springframework.data.mongodb.core.HintFunction
      org.springframework.data.mongodb.core.IndexConverters
      org.springframework.data.mongodb.core.MappedDocument
      org.springframework.data.mongodb.core.MappingMongoJsonSchemaCreator
      org.springframework.data.mongodb.core.MongoAction
      org.springframework.data.mongodb.core.MongoActionOperation
      org.springframework.data.mongodb.core.MongoDataIntegrityViolationException
      org.springframework.data.mongodb.core.MongoExceptionTranslator
      org.springframework.data.mongodb.core.MongoJsonSchemaCreator
      org.springframework.data.mongodb.core.PropertyOperations
      org.springframework.data.mongodb.core.QueryOperations
      org.springframework.data.mongodb.core.QueryResultConverter
      org.springframework.data.mongodb.core.ReadConcernAware
      org.springframework.data.mongodb.core.ReadPreferenceAware
      org.springframework.data.mongodb.core.ReplaceOptions
      org.springframework.data.mongodb.core.ScrollUtils
      org.springframework.data.mongodb.core.SourceAwareDocument
      org.springframework.data.mongodb.core.ViewOptions
      org.springframework.data.mongodb.core.WriteConcernAware
      org.springframework.data.mongodb.core.WriteConcernResolver
      org.springframework.data.mongodb.core.WriteResultChecking
      org.springframework.data.mongodb.config.EnableMongoAuditing
      org.springframework.data.mongodb.core.convert.encryption.ExplicitEncryptionContext
      org.springframework.data.mongodb.core.encryption.Encryption
      org.springframework.data.mongodb.core.encryption.EncryptionContext
      org.springframework.data.mongodb.core.encryption.EncryptionKey
      org.springframework.data.mongodb.core.encryption.EncryptionKeyResolver
      org.springframework.data.mongodb.core.encryption.EncryptionOptions
      org.springframework.data.mongodb.core.encryption.KeyAltName
      org.springframework.data.mongodb.core.encryption.KeyId
      org.springframework.data.mongodb.core.index.CompoundIndex
      org.springframework.data.mongodb.core.index.CompoundIndexDefinition
      org.springframework.data.mongodb.core.index.CompoundIndexes
      org.springframework.data.mongodb.core.index.GeospatialIndex
      org.springframework.data.mongodb.core.index.GeoSpatialIndexed
      org.springframework.data.mongodb.core.index.GeoSpatialIndexType
      org.springframework.data.mongodb.core.index.HashedIndex
      org.springframework.data.mongodb.core.index.HashIndexed
      org.springframework.data.mongodb.core.index.Index
      org.springframework.data.mongodb.core.index.IndexDefinition
      org.springframework.data.mongodb.core.index.IndexDirection
      org.springframework.data.mongodb.core.index.Indexed
      org.springframework.data.mongodb.core.index.IndexField
      org.springframework.data.mongodb.core.index.IndexFilter
      org.springframework.data.mongodb.core.index.IndexInfo
      org.springframework.data.mongodb.core.index.IndexOptions
      org.springframework.data.mongodb.core.index.IndexPredicate
      org.springframework.data.mongodb.core.index.IndexResolver
      org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver
      org.springframework.data.mongodb.core.index.PartialIndexFilter
      org.springframework.data.mongodb.core.index.SearchIndexDefinition
      org.springframework.data.mongodb.core.index.SearchIndexInfo
      org.springframework.data.mongodb.core.index.SearchIndexStatus
      org.springframework.data.mongodb.core.index.TextIndexDefinition
      org.springframework.data.mongodb.core.index.TextIndexed
      org.springframework.data.mongodb.core.index.VectorIndex
      org.springframework.data.mongodb.core.index.WildcardIndex
      org.springframework.data.mongodb.core.index.WildcardIndexed
      org.springframework.data.mongodb.core.messaging.ChangeStreamRequest
      org.springframework.data.mongodb.core.messaging.LazyMappingDelegatingMessage
      org.springframework.data.mongodb.core.messaging.Message
      org.springframework.data.mongodb.core.messaging.MessageListener
      org.springframework.data.mongodb.core.messaging.SimpleMessage
      org.springframework.data.mongodb.core.messaging.SubscriptionRequest
      org.springframework.data.mongodb.core.messaging.TailableCursorRequest
      org.springframework.data.mongodb.gridfs.AntPath
      org.springframework.data.mongodb.gridfs.GridFsCriteria
      """.lines().filter(line -> !line.isBlank()).map(String::strip).toList());

  @Test
  void auditedMongoDependencyJarClassSnapshotsAreExact() throws Exception {
    assertJarClassSnapshot(MongoTemplate.class, "spring-data-mongodb-5.1.0.jar", 1668,
        "ed41dc89ba4aa2684ed90f7deb2a7ebf4969e788f213043266ebfde0458a1cd1");
    assertJarClassSnapshot(MongoClient.class, "mongodb-driver-sync-5.8.0.jar", 80,
        "1a8d1d1021d23d3b9fbe5ff881fd2c9143724216cc74807726dd69e810562d18");
    assertJarClassSnapshot(MongoClientSettings.class, "mongodb-driver-core-5.8.0.jar", 1356,
        "c14a7f42d611d50af5c28d15bd25eccdf93d06d40646f835d74c57498cd46a1a");
  }

  @Test
  void everyCurrentAccessCandidateIsForbiddenUnlessLiterallyAuditedAsInert() throws Exception {
    var jarPaths = List.of(
        jarPath(MongoTemplate.class),
        jarPath(MongoClient.class),
        jarPath(MongoClientSettings.class));
    var candidates = new TreeSet<String>();
    for (var jarPath : jarPaths) {
      classesInJar(jarPath).stream()
          .filter(MongoPersistenceBoundaryRulesTest::isAuditedAccessCandidate)
          .forEach(candidates::add);
    }

    assertThat(candidates).hasSize(853);
    assertThat(sha256(candidates))
        .isEqualTo("4d9955cf85bc31f305583c8cebe7d01b9f7fe1dbab53e612796f8c961dc9080f");
    assertThat(AUDITED_INERT_ACCESS_CANDIDATES).hasSize(102);
    assertThat(candidates).containsAll(AUDITED_INERT_ACCESS_CANDIDATES);

    var classes = importJars(jarPaths);
    var unclassified = candidates.stream()
        .filter(name -> !AUDITED_INERT_ACCESS_CANDIDATES.contains(name))
        .filter(name -> !MongoPersistenceBoundaryRules.isForbiddenMongoType(classes.get(name)))
        .toList();
    var inertFalsePositives = AUDITED_INERT_ACCESS_CANDIDATES.stream()
        .filter(name -> MongoPersistenceBoundaryRules.isForbiddenMongoType(classes.get(name)))
        .sorted()
        .toList();

    assertThat(unclassified).isEmpty();
    assertThat(inertFalsePositives).isEmpty();
  }

  @Test
  void familyClassifierRejectsAuditedDefaultAndInternalImplementations() {
    var classes = new ClassFileImporter().importPackages(
        "com.mongodb.client.gridfs",
        "com.mongodb.client.internal",
        "org.springframework.data.mongodb",
        "org.springframework.data.mongodb.core",
        "org.springframework.data.mongodb.gridfs");
    var implementations = Set.copyOf("""
        com.mongodb.client.gridfs.GridFSBucketImpl
        com.mongodb.client.gridfs.GridFSDownloadStreamImpl
        com.mongodb.client.gridfs.GridFSFindIterableImpl
        com.mongodb.client.gridfs.GridFSUploadStreamImpl
        com.mongodb.client.internal.ClientEncryptionImpl
        com.mongodb.client.internal.FindIterableImpl
        com.mongodb.client.internal.MongoChangeStreamCursorImpl
        com.mongodb.client.internal.MongoClientImpl
        com.mongodb.client.internal.MongoCollectionImpl
        com.mongodb.client.internal.MongoDatabaseImpl
        org.springframework.data.mongodb.core.DefaultBulkOperations
        org.springframework.data.mongodb.core.DefaultIndexOperations
        org.springframework.data.mongodb.core.DefaultReactiveBulkOperations
        org.springframework.data.mongodb.core.DefaultReactiveIndexOperations
        org.springframework.data.mongodb.core.DefaultScriptOperations
        org.springframework.data.mongodb.core.MongoTemplate
        org.springframework.data.mongodb.core.ReactiveMongoTemplate
        org.springframework.data.mongodb.core.MongoDatabaseFactorySupport
        org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
        org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory
        org.springframework.data.mongodb.core.index.DefaultSearchIndexOperations
        org.springframework.data.mongodb.gridfs.GridFsTemplate
        org.springframework.data.mongodb.gridfs.ReactiveGridFsTemplate
        org.springframework.data.mongodb.MongoResourceHolder
        org.springframework.data.mongodb.ReactiveMongoResourceHolder
        """.lines().filter(line -> !line.isBlank()).map(String::strip).toList());

    assertThat(implementations).hasSize(25);
    implementations.forEach(name -> assertThat(
        MongoPersistenceBoundaryRules.isForbiddenMongoType(classes.get(name)))
        .as(name)
        .isTrue());
  }

  @Test
  void compiledRuleRejectsEveryDirectMongoApiAndMongoRepositoryBase() {
    var fixtures = new ClassFileImporter().importPackages(FIXTURE_ROOT);
    var rules = new MongoPersistenceBoundaryRules(FIXTURE_ROOT, Set.of());
    var details = rules.zeroBypassRule().evaluate(fixtures).getFailureReport().getDetails();

    assertThat(AUDITED_MONGO_ACCESS_TYPES).hasSize(91);
    var expected = new java.util.TreeSet<String>();
    AUDITED_MONGO_ACCESS_TYPES.forEach(target -> expected.add(
        ACCESS_FIXTURE + " directly depends on " + target));
    expected.add(FIXTURE_ROOT + ".ForbiddenSpringRepository directly depends on "
        + "org.springframework.data.mongodb.repository.MongoRepository");
    assertThat(details)
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  private static void assertJarClassSnapshot(
      Class<?> anchor, String expectedFileName, int expectedCount, String expectedSha256)
      throws Exception {
    var jarPath = jarPath(anchor);
    assertThat(jarPath.getFileName().toString()).isEqualTo(expectedFileName);
    var classes = classesInJar(jarPath, false);
    assertThat(classes).hasSize(expectedCount);
    assertThat(sha256(classes)).isEqualTo(expectedSha256);
  }

  private static Path jarPath(Class<?> anchor) throws Exception {
    return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private static List<String> classesInJar(Path jarPath) throws Exception {
    return classesInJar(jarPath, true);
  }

  private static List<String> classesInJar(Path jarPath, boolean topLevelOnly) throws Exception {
    try (var jar = new JarFile(jarPath.toFile())) {
      return jar.stream()
          .map(entry -> entry.getName())
          .filter(name -> name.endsWith(".class"))
          .filter(name -> !topLevelOnly || !name.contains("$")
              && !name.endsWith("package-info.class")
              && !name.endsWith("module-info.class"))
          .map(name -> topLevelOnly
              ? name.substring(0, name.length() - ".class".length()).replace('/', '.')
              : name)
          .sorted()
          .toList();
    }
  }

  private static JavaClasses importJars(List<Path> paths) throws Exception {
    var jars = new ArrayList<JarFile>();
    try {
      for (var path : paths) {
        jars.add(new JarFile(path.toFile()));
      }
      return new ClassFileImporter().importJars(jars);
    } finally {
      for (var jar : jars) {
        jar.close();
      }
    }
  }

  private static String sha256(Iterable<String> values) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256")
        .digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
  }

  private static boolean isAuditedAccessCandidate(String name) {
    if (name.startsWith("com.mongodb.internal.")) {
      return true;
    }
    if (name.startsWith("com.mongodb.client.")) {
      return !startsWithAny(name, Set.of(
          "com.mongodb.client.cursor.",
          "com.mongodb.client.gridfs.codecs.",
          "com.mongodb.client.gridfs.model.",
          "com.mongodb.client.model.",
          "com.mongodb.client.result."));
    }
    if (name.startsWith("com.mongodb.session.")) {
      return true;
    }
    if (isImmediateChild(name, "org.springframework.data.mongodb.")
        || isImmediateChild(name, "org.springframework.data.mongodb.core.")) {
      return true;
    }
    var simpleName = name.substring(name.lastIndexOf('.') + 1);
    if (name.startsWith("org.springframework.data.mongodb.core.convert.encryption.")) {
      return true;
    }
    if (name.startsWith("org.springframework.data.mongodb.core.convert.")) {
      return simpleName.contains("DbRef")
          || simpleName.contains("ReferenceLoader")
          || simpleName.contains("ReferenceResolver")
          || simpleName.contains("ReferenceLookupDelegate");
    }
    if (startsWithAny(name, Set.of(
        "org.springframework.data.mongodb.config.",
        "org.springframework.data.mongodb.core.encryption.",
        "org.springframework.data.mongodb.core.index.",
        "org.springframework.data.mongodb.core.messaging.",
        "org.springframework.data.mongodb.gridfs."))) {
      return true;
    }
    var repositoryRoot = "org.springframework.data.mongodb.repository.";
    if (!name.startsWith(repositoryRoot)) {
      return false;
    }
    var repositoryName = name.substring(repositoryRoot.length());
    return repositoryName.contains(".")
        || repositoryName.equals("MongoRepository")
        || repositoryName.equals("ReactiveMongoRepository");
  }

  private static boolean isImmediateChild(String name, String root) {
    return name.startsWith(root) && !name.substring(root.length()).contains(".");
  }

  private static boolean startsWithAny(String name, Set<String> prefixes) {
    return prefixes.stream().anyMatch(name::startsWith);
  }
}

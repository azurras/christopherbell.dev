package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
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
      org.springframework.data.mongodb.core.convert.DefaultDbRefResolver
      org.springframework.data.mongodb.core.convert.MongoDatabaseFactoryReferenceLoader
      org.springframework.data.mongodb.core.index.DefaultSearchIndexOperations
      org.springframework.data.mongodb.core.index.IndexOperations
      org.springframework.data.mongodb.core.index.ReactiveIndexOperations
      org.springframework.data.mongodb.core.index.SearchIndexOperations
      org.springframework.data.mongodb.gridfs.GridFsOperations
      org.springframework.data.mongodb.gridfs.GridFsTemplate
      org.springframework.data.mongodb.gridfs.ReactiveGridFsOperations
      org.springframework.data.mongodb.gridfs.ReactiveGridFsTemplate
      org.springframework.data.mongodb.repository.support.MongoRepositoryFactory
      org.springframework.data.mongodb.repository.support.MongoRepositoryFactoryBean
      org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactory
      org.springframework.data.mongodb.repository.support.ReactiveMongoRepositoryFactoryBean
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
  void compiledRuleRejectsEveryDirectMongoApiAndSpringRepositoryBase() {
    var fixtures = new ClassFileImporter().importPackages(FIXTURE_ROOT);
    var rules = new MongoPersistenceBoundaryRules(FIXTURE_ROOT, Set.of());
    var details = rules.zeroBypassRule().evaluate(fixtures).getFailureReport().getDetails();

    assertThat(AUDITED_MONGO_ACCESS_TYPES).hasSize(74);
    var expected = new java.util.TreeSet<String>();
    AUDITED_MONGO_ACCESS_TYPES.forEach(target -> expected.add(
        ACCESS_FIXTURE + " directly depends on " + target));
    expected.add(FIXTURE_ROOT + ".ForbiddenSpringRepository directly depends on "
        + "org.springframework.data.repository.CrudRepository");
    expected.add(FIXTURE_ROOT + ".ForbiddenSpringRepository directly depends on "
        + "org.springframework.data.repository.Repository");
    assertThat(details)
        .containsExactlyInAnyOrderElementsOf(expected);
  }

  private static void assertJarClassSnapshot(
      Class<?> anchor, String expectedFileName, int expectedCount, String expectedSha256)
      throws Exception {
    var jarPath = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertThat(jarPath.getFileName().toString()).isEqualTo(expectedFileName);
    try (var jar = new JarFile(jarPath.toFile())) {
      var classes = jar.stream()
          .map(entry -> entry.getName())
          .filter(name -> name.endsWith(".class"))
          .sorted()
          .toList();
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(String.join("\n", classes).getBytes(StandardCharsets.UTF_8));
      assertThat(classes).hasSize(expectedCount);
      assertThat(HexFormat.of().formatHex(digest)).isEqualTo(expectedSha256);
    }
  }
}

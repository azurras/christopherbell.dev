package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MongoPersistenceBoundaryRulesTest {
  private static final String FIXTURE_ROOT =
      "dev.christopherbell.architecture.fixture.ops.mongo";
  private static final String ACCESS_FIXTURE = FIXTURE_ROOT + ".ForbiddenMongoApiDependencies";
  private static final Set<String> AUDITED_MONGO_ACCESS_TYPES = Set.copyOf("""
      com.mongodb.client.MongoClient
      com.mongodb.client.MongoClientFactory
      com.mongodb.client.MongoClients
      com.mongodb.client.MongoCollection
      com.mongodb.client.MongoDatabase
      org.springframework.data.mongodb.MongoDatabaseFactory
      org.springframework.data.mongodb.ReactiveMongoDatabaseFactory
      org.springframework.data.mongodb.core.ExecutableAggregationOperation
      org.springframework.data.mongodb.core.ExecutableFindOperation
      org.springframework.data.mongodb.core.ExecutableInsertOperation
      org.springframework.data.mongodb.core.ExecutableMapReduceOperation
      org.springframework.data.mongodb.core.ExecutableRemoveOperation
      org.springframework.data.mongodb.core.ExecutableUpdateOperation
      org.springframework.data.mongodb.core.FluentMongoOperations
      org.springframework.data.mongodb.core.MongoClientFactoryBean
      org.springframework.data.mongodb.core.MongoOperations
      org.springframework.data.mongodb.core.MongoTemplate
      org.springframework.data.mongodb.core.ReactiveAggregationOperation
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
      """.lines().filter(line -> !line.isBlank()).map(String::strip).toList());

  @Test
  void compiledRuleRejectsEveryDirectMongoApiAndSpringRepositoryBase() {
    var fixtures = new ClassFileImporter().importPackages(FIXTURE_ROOT);
    var rules = new MongoPersistenceBoundaryRules(FIXTURE_ROOT, Set.of());
    var details = rules.zeroBypassRule().evaluate(fixtures).getFailureReport().getDetails();

    assertThat(AUDITED_MONGO_ACCESS_TYPES).hasSize(30);
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
}

package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MongoPersistenceBoundaryRulesTest {
  private static final String FIXTURE_ROOT =
      "dev.christopherbell.architecture.fixture.ops.mongo";

  @Test
  void compiledRuleRejectsEveryDirectMongoApiAndSpringRepositoryBase() {
    var fixtures = new ClassFileImporter().importPackages(FIXTURE_ROOT);
    var rules = new MongoPersistenceBoundaryRules(FIXTURE_ROOT, Set.of());
    var details = rules.zeroBypassRule().evaluate(fixtures).getFailureReport().getDetails();

    assertThat(details)
        .anyMatch(detail -> detail.contains("MongoOperations"))
        .anyMatch(detail -> detail.contains("MongoTemplate"))
        .anyMatch(detail -> detail.contains("ReactiveMongoTemplate"))
        .anyMatch(detail -> detail.contains("MongoClient"))
        .anyMatch(detail -> detail.contains("MongoDatabase"))
        .anyMatch(detail -> detail.contains("MongoDatabaseFactory"))
        .anyMatch(detail -> detail.contains("MongoCollection"))
        .anyMatch(detail -> detail.contains("ForbiddenSpringRepository")
            && detail.contains("Repository"));
  }
}

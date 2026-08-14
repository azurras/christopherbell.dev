package dev.christopherbell.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaModifier;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

class MongoRepositoryProxyCompatibilityTest {

  @Test
  void repositoryBeansRemainProxyableForPersistenceExceptionTranslation() {
    var productionClasses = new ClassFileImporter().importPackages("dev.christopherbell");

    classes()
        .that().areAnnotatedWith(Repository.class)
        .or().areAnnotatedWith(MongoPersistence.class)
        .or().areAnnotatedWith(PostgresPersistence.class)
        .and().resideOutsideOfPackage("..architecture.fixture..")
        .should().notHaveModifier(JavaModifier.FINAL)
        .check(productionClasses);
  }
}

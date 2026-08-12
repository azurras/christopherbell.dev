package dev.christopherbell.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.domain.JavaModifier;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Repository;

class MongoRepositoryProxyCompatibilityTest {

  @Test
  void repositoryBeansRemainProxyableForPersistenceExceptionTranslation() {
    var productionClasses = new ClassFileImporter().importPackages("dev.christopherbell");

    classes()
        .that().areAnnotatedWith(Repository.class)
        .should().notHaveModifier(JavaModifier.FINAL)
        .check(productionClasses);
  }
}

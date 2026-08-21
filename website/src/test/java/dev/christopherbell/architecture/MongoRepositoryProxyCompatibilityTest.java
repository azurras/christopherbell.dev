package dev.christopherbell.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;

class MongoRepositoryProxyCompatibilityTest {

  @Test
  void repositoryBeansRemainProxyableForPersistenceExceptionTranslation() {
    var productionClasses = productionClasses();

    classes()
        .that().areAnnotatedWith(Repository.class)
        .or().areAnnotatedWith(MongoPersistence.class)
        .or().areAnnotatedWith(PostgresPersistence.class)
        .and().resideOutsideOfPackage("..architecture.fixture..")
        .should().notHaveModifier(JavaModifier.FINAL)
        .check(productionClasses);
  }

  @Test
  void repositoryMarkersExcludeBackendConfigurationAndSupportTypes() {
    var productionClasses = productionClasses();

    classes()
        .that().areAnnotatedWith(MongoPersistence.class)
        .should().notBeAnnotatedWith(Configuration.class)
        .andShould().haveSimpleNameNotEndingWith("Support")
        .check(productionClasses);
  }

  private static JavaClasses productionClasses() {
    return new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("dev.christopherbell");
  }
}

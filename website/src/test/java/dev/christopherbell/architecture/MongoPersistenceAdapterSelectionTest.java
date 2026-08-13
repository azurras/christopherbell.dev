package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

class MongoPersistenceAdapterSelectionTest {

  @Test
  void everyMongoRepositoryAndMongoInfrastructureComponentUsesTheMongoSelector() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var adapters = classes.stream()
        .filter(javaClass -> !javaClass.getPackageName()
            .equals("dev.christopherbell.configuration.persistence"))
        .filter(javaClass -> javaClass.isAnnotatedWith(Repository.class)
            || javaClass.getPackageName().startsWith("dev.christopherbell.configuration.mongo")
                && (javaClass.isAnnotatedWith(Component.class)
                    || javaClass.isAnnotatedWith(Configuration.class)))
        .toList();

    assertThat(adapters).isNotEmpty();
    assertThat(adapters)
        .allSatisfy(adapter -> assertThat(adapter.isAnnotatedWith(MongoPersistence.class)).isTrue());
  }
}

package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.christopherbell.configuration.persistence.PostgresPersistence;
import org.junit.jupiter.api.Test;

class PostgresqlPersistenceBoundaryRulesTest {

  @Test
  void jooqDependenciesStayInsidePostgresqlConfigurationGeneratedCodeAndAdapters() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var violations = classes.stream()
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.persistence.jooq"))
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.configuration.postgresql"))
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.configuration.persistence"))
        .filter(javaClass -> !javaClass.getPackageName()
            .startsWith("dev.christopherbell.codegen"))
        .filter(javaClass -> !javaClass.getName().contains("Test"))
        .filter(javaClass -> !javaClass.getSimpleName().startsWith("Postgres"))
        .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
            .map(dependency -> dependency.getTargetClass().getPackageName())
            .anyMatch(packageName -> packageName.startsWith("org.jooq")
                || packageName.startsWith("org.postgresql")
                || packageName.equals("java.sql")))
        .map(javaClass -> javaClass.getName())
        .sorted()
        .toList();

    assertThat(violations).isEmpty();
  }

  @Test
  void taskThreePostgresqlAdaptersAreSelectedAndImplementPorts() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var adapters = classes.stream()
        .filter(javaClass -> javaClass.getSimpleName().startsWith("Postgres"))
        .filter(javaClass -> javaClass.isAnnotatedWith(PostgresPersistence.class))
        .filter(javaClass -> !javaClass.getName().contains("Test"))
        .toList();

    assertThat(adapters).isNotEmpty();
    assertThat(adapters).allSatisfy(adapter ->
        assertThat(adapter.getRawInterfaces()).as(adapter.getName()).isNotEmpty());
  }

  @Test
  void postgresqlAdaptersDoNotDependOnAnotherContextsPostgresqlAdapter() {
    var classes = new ClassFileImporter().importPackages("dev.christopherbell");
    var violations = classes.stream()
        .filter(javaClass -> javaClass.isAnnotatedWith(PostgresPersistence.class))
        .flatMap(javaClass -> javaClass.getDirectDependenciesFromSelf().stream())
        .filter(dependency -> dependency.getTargetClass()
            .isAnnotatedWith(PostgresPersistence.class))
        .filter(dependency -> !topLevelArea(dependency.getOriginClass().getPackageName())
            .equals(topLevelArea(dependency.getTargetClass().getPackageName())))
        .map(dependency -> "%s -> %s".formatted(
            dependency.getOriginClass().getName(), dependency.getTargetClass().getName()))
        .distinct()
        .sorted()
        .toList();

    assertThat(violations).isEmpty();
  }

  private static String topLevelArea(String packageName) {
    var prefix = "dev.christopherbell.";
    var remainder = packageName.substring(prefix.length());
    var separator = remainder.indexOf('.');
    return separator < 0 ? remainder : remainder.substring(0, separator);
  }
}

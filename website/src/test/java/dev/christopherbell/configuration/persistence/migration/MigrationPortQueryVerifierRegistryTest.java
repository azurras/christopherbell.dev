package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class MigrationPortQueryVerifierRegistryTest {
  @Test
  void executableRegistryNamesExactlyCoverEveryDeclaredCatalogPortQuery() throws IOException {
    var catalog = loadCatalog();
    var declared = new LinkedHashSet<String>();
    catalog.kinds().forEach(kind -> declared.addAll(kind.portQueries()));

    var registry = MigrationPortQueryVerifierRegistry.from(catalog);

    assertThat(registry.names()).containsExactlyInAnyOrderElementsOf(declared);
    assertThat(registry.names()).hasSize(82);
    assertThat(registry.declarationCount()).isEqualTo(153);
    assertThat(catalog.kinds().stream().mapToInt(kind -> kind.portQueries().size()).sum())
        .isEqualTo(153);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void everyExplicitDeclarationReferencesRealTypedColumns() throws Exception {
    var catalog = loadCatalog();
    var registry = MigrationPortQueryVerifierRegistry.from(catalog);
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(registry.schemaViolations(connection, database.prefix(), catalog)).isEmpty();
    }
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MigrationPortQueryVerifierRegistryTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}

package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class MigrationTransformerRegistryTest {
  @Test
  void checkedInCatalogBindsOneExactTransformerForEveryManifestKind() throws IOException {
    var catalog = loadCatalog();
    var registry = MigrationTransformerRegistry.from(catalog);

    assertThat(registry.sourceKinds()).hasSize(52);
    assertThat(registry.sourceKinds())
        .containsExactlyElementsOf(catalog.kinds().stream()
            .sorted(Comparator.comparingInt(PostgresqlMigrationCatalog.Kind::loadOrder))
            .map(PostgresqlMigrationCatalog.Kind::sourceKind)
            .toList());
    assertThat(catalog.kinds()).allSatisfy(kind -> {
      var transformer = registry.require(kind.sourceKind());
      assertThat(transformer.sourceKind()).isEqualTo(kind.sourceKind());
      assertThat(transformer.getClass().getName()).isEqualTo(kind.transformerClass());
    });
  }

  @Test
  void registryRejectsAClassThatDoesNotImplementTheDeclaredKind() throws IOException {
    var catalog = loadCatalog();
    var first = catalog.kinds().getFirst();
    var wrong = new PostgresqlMigrationCatalog.Kind(
        first.sourceCollection(), first.sourceKind(), first.sourceSchemaVersion(),
        first.transformerVersion(), first.identifierType(), first.targetSchema(),
        first.targetTables(), first.loadOrder(), first.dependsOnKinds(), first.keyMapping(),
        first.fieldMappings(), first.deleteBehavior(), first.versionSemantics(),
        first.expirySemantics(), first.canonicalHash(), first.reconciliation(),
        first.portQueries(), PostLikeTransformer.class.getName());
    var kinds = new java.util.ArrayList<>(catalog.kinds());
    kinds.set(0, wrong);

    assertThatThrownBy(() -> MigrationTransformerRegistry.from(
        new PostgresqlMigrationCatalog(catalog.version(), kinds)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration transformer registry is invalid.");
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MigrationTransformerRegistryTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}

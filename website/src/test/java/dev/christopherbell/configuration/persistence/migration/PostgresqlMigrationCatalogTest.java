package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;

class PostgresqlMigrationCatalogTest {
  private static final String RESOURCE = "db/migration/postgresql-migration-catalog.yml";
  private static final Set<String> CUTOVER_FIELDS = Set.of(
      "state", "manifestDigest", "ownerToken", "release", "backupIdentity",
      "evidenceDigest", "revision", "stageIndex", "publishIndex", "dropIndex", "completed",
      "legacyDropped", "intent", "presentSources", "expectedKindMetrics");

  @Test
  void canonicalCatalogCoversEveryManifestKindAndPersistedFieldExactlyOnce() throws Exception {
    var catalog = loadCatalog();
    var catalogByKind = catalog.kinds().stream().collect(Collectors.toUnmodifiableMap(
        PostgresqlMigrationCatalog.Kind::sourceKind, Function.identity()));
    var manifestByKind = DomainCollectionManifest.ALL_KINDS.stream().collect(
        Collectors.toUnmodifiableMap(
            DomainCollectionManifest.KindDefinition::kind, Function.identity()));

    assertThat(catalog.kinds()).hasSize(52);
    assertThat(catalogByKind.keySet()).containsExactlyInAnyOrderElementsOf(manifestByKind.keySet());
    assertThat(catalog.kinds().stream()
        .map(PostgresqlMigrationCatalog.Kind::targetSchema)
        .collect(Collectors.toSet()))
        .containsExactlyInAnyOrder(
            "identity", "social", "communication", "federation", "music", "shared_folder",
            "mobility", "lunch", "canes", "platform");

    for (var manifest : DomainCollectionManifest.ALL_KINDS) {
      var target = catalogByKind.get(manifest.kind());
      assertThat(target.sourceCollection()).as(manifest.kind()).isEqualTo(manifest.collection());
      assertThat(target.sourceSchemaVersion()).as(manifest.kind())
          .isEqualTo(manifest.schemaVersion());
      assertThat(target.fieldMappings().keySet()).as(manifest.kind())
          .containsExactlyInAnyOrderElementsOf(persistedFields(manifest));
      assertThat(target.keyMapping().sourcePath()).as(manifest.kind())
          .isEqualTo(persistedIdField(manifest));
      assertThat(target.canonicalHash()).as(manifest.kind()).isEqualTo("sha256-rfc8785-v1");
      assertThat(target.reconciliation()).as(manifest.kind())
          .contains("row-count", "canonical-record-hash");
      assertThat(target.portQueries()).as(manifest.kind()).isNotEmpty();
    }
  }

  @Test
  void everyCatalogTargetIsAConcreteDeclaredRelationalColumn() {
    var catalog = loadCatalog();

    assertThat(catalog.kinds()).allSatisfy(kind -> {
      assertThat(kind.targetTables()).doesNotHaveDuplicates();
      assertThat(kind.fieldMappings()).allSatisfy((field, mapping) -> {
        if (!"constant-kind".equals(mapping.conversion())) {
          assertThat(mapping.targets()).as(kind.sourceKind() + "." + field).isNotEmpty();
        }
        assertThat(mapping.targets()).as(kind.sourceKind() + "." + field)
            .allMatch(target -> !target.contains("*") && !target.toLowerCase().contains("json"));
      });
    });
  }

  private static PostgresqlMigrationCatalog loadCatalog() {
    try (InputStream input = PostgresqlMigrationCatalogTest.class.getClassLoader()
        .getResourceAsStream(RESOURCE)) {
      assertThat(input).as(RESOURCE).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Migration catalog resource could not be closed.", failure);
    }
  }

  private static Set<String> persistedFields(
      DomainCollectionManifest.KindDefinition definition) throws ClassNotFoundException {
    if (definition.kind().equals("domain_collection_cutover")) {
      return CUTOVER_FIELDS;
    }
    var owner = Class.forName(definition.ownerTypeName());
    return Arrays.stream(owner.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .filter(field -> !field.isSynthetic())
        .filter(field -> !field.isAnnotationPresent(Id.class))
        .map(java.lang.reflect.Field::getName)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String persistedIdField(
      DomainCollectionManifest.KindDefinition definition) throws ClassNotFoundException {
    if (definition.kind().equals("domain_collection_cutover")) {
      return "id";
    }
    var owner = Class.forName(definition.ownerTypeName());
    return Arrays.stream(owner.getDeclaredFields())
        .filter(field -> field.isAnnotationPresent(Id.class))
        .map(java.lang.reflect.Field::getName)
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Missing persisted ID field for " + definition.kind()));
  }
}

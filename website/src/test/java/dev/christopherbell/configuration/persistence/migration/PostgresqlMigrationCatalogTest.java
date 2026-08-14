package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

  @Test
  void nullableSourceStatesAndVinResponseUseLosslessConversions() {
    var byKind = loadCatalog().kinds().stream().collect(Collectors.toUnmodifiableMap(
        PostgresqlMigrationCatalog.Kind::sourceKind, Function.identity()));

    var vinCache = byKind.get("vin_decode_cache");
    assertThat(vinCache.fieldMappings().get("response"))
        .extracting(
            PostgresqlMigrationCatalog.FieldMapping::conversion,
            PostgresqlMigrationCatalog.FieldMapping::missing,
            PostgresqlMigrationCatalog.FieldMapping::nullValue)
        .containsExactly("vin-response-flattened", "allow", "allow");
    assertThat(Set.of("createdOn", "expiresOn", "lastUpdatedOn", "refreshedOn"))
        .allSatisfy(field -> assertThat(vinCache.fieldMappings().get(field))
            .extracting(
                PostgresqlMigrationCatalog.FieldMapping::missing,
                PostgresqlMigrationCatalog.FieldMapping::nullValue)
            .containsExactly("allow", "allow"));

    assertThat(vinCache.fieldMappings().get("response").targets())
        .contains(
            "vin_decode_cache.response_present",
            "vin_decode_cache.raw_decoded_values_present");
    assertThat(byKind.get("nhtsa_import_state").fieldMappings().get("permanentlyDisabled"))
        .extracting(
            PostgresqlMigrationCatalog.FieldMapping::missing,
            PostgresqlMigrationCatalog.FieldMapping::nullValue)
        .containsExactly("allow", "allow");
    var randomVin = byKind.get("random_vin_import_state");
    assertThat(randomVin.fieldMappings().get("permanentlyDisabled"))
        .extracting(
            PostgresqlMigrationCatalog.FieldMapping::missing,
            PostgresqlMigrationCatalog.FieldMapping::nullValue)
        .containsExactly("allow", "allow");
    assertThat(randomVin.fieldMappings().get("robotsPolicy").targets())
        .contains("random_vin_import_state.robots_policy_present");
    assertThat(byKind.get("vote").fieldMappings().get("vote"))
        .extracting(
            PostgresqlMigrationCatalog.FieldMapping::missing,
            PostgresqlMigrationCatalog.FieldMapping::nullValue)
        .containsExactly("allow", "allow");
    var admin = byKind.get("admin_activity");
    assertThat(Set.of("targetLabel", "reason", "message", "beforeValues", "afterValues", "metadata"))
        .allSatisfy(field -> assertThat(admin.fieldMappings().get(field))
            .extracting(
                PostgresqlMigrationCatalog.FieldMapping::missing,
                PostgresqlMigrationCatalog.FieldMapping::nullValue)
            .containsExactly("allow", "allow"));
    assertThat(admin.fieldMappings().get("beforeValues").targets())
        .contains("admin_activity.before_values_present");
    assertThat(admin.fieldMappings().get("afterValues").targets())
        .contains("admin_activity.after_values_present");
    assertThat(admin.fieldMappings().get("metadata").targets())
        .contains("admin_activity.metadata_present");

    assertThat(byKind.get("preference").fieldMappings().get("radiusMiles"))
        .extracting(
            PostgresqlMigrationCatalog.FieldMapping::conversion,
            PostgresqlMigrationCatalog.FieldMapping::missing,
            PostgresqlMigrationCatalog.FieldMapping::nullValue)
        .containsExactly("integer", "allow", "allow");
  }

  @Test
  void referencedKindCannotLoadAfterItsReferencingKind() {
    var catalog = loadCatalog();
    var invalidKinds = catalog.kinds().stream()
        .map(kind -> kind.sourceKind().equals("notification")
            ? withLoadOrder(kind, 205)
            : kind)
        .toList();

    assertThatThrownBy(() -> PostgresqlMigrationCatalogValidator.validate(
        new PostgresqlMigrationCatalog(catalog.version(), invalidKinds)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("notification.dependsOnKinds");
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

  private static PostgresqlMigrationCatalog.Kind withLoadOrder(
      PostgresqlMigrationCatalog.Kind kind, int loadOrder) {
    return new PostgresqlMigrationCatalog.Kind(
        kind.sourceCollection(),
        kind.sourceKind(),
        kind.sourceSchemaVersion(),
        kind.transformerVersion(),
        kind.identifierType(),
        kind.targetSchema(),
        kind.targetTables(),
        loadOrder,
        kind.dependsOnKinds(),
        kind.keyMapping(),
        kind.fieldMappings(),
        kind.deleteBehavior(),
        kind.versionSemantics(),
        kind.expirySemantics(),
        kind.canonicalHash(),
        kind.reconciliation(),
        kind.portQueries(),
        kind.transformerClass());
  }

  private static Set<String> persistedFields(
      DomainCollectionManifest.KindDefinition definition) throws ClassNotFoundException {
    if (definition.kind().equals("domain_collection_cutover")) {
      return CUTOVER_FIELDS;
    }
    var owner = Class.forName(definition.ownerTypeName());
    return persistedFields(owner)
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
    return persistedFields(owner)
        .filter(field -> field.isAnnotationPresent(Id.class))
        .map(java.lang.reflect.Field::getName)
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Missing persisted ID field for " + definition.kind()));
  }

  private static Stream<Field> persistedFields(Class<?> owner) {
    return Stream.<Class<?>>iterate(owner, type -> type != null, type -> type.getSuperclass())
        .flatMap(type -> Arrays.stream(type.getDeclaredFields()));
  }
}

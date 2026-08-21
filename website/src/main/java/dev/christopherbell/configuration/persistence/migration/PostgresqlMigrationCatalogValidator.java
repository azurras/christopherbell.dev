package dev.christopherbell.configuration.persistence.migration;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.annotation.Id;

/** Fails closed when catalog kinds or source fields drift from the persisted manifest. */
final class PostgresqlMigrationCatalogValidator {
  private static final Set<String> CUTOVER_FIELDS = Set.of(
      "state", "manifestDigest", "ownerToken", "release", "backupIdentity",
      "evidenceDigest", "revision", "stageIndex", "publishIndex", "dropIndex", "completed",
      "legacyDropped", "intent", "presentSources", "expectedKindMetrics");

  private PostgresqlMigrationCatalogValidator() {}

  static PostgresqlMigrationCatalog validate(PostgresqlMigrationCatalog catalog) {
    var manifests = DomainCollectionManifest.ALL_KINDS.stream().collect(Collectors.toUnmodifiableMap(
        DomainCollectionManifest.KindDefinition::kind, Function.identity()));
    var kinds = catalog.kinds().stream().collect(Collectors.toUnmodifiableMap(
        PostgresqlMigrationCatalog.Kind::sourceKind, Function.identity()));
    if (!kinds.keySet().equals(manifests.keySet())) {
      throw invalid("kinds.sourceKind");
    }

    for (var manifest : manifests.values()) {
      var kind = kinds.get(manifest.kind());
      if (!kind.sourceCollection().equals(manifest.collection())) {
        throw invalid(kind.sourceKind() + ".sourceCollection");
      }
      if (!kind.sourceOwner().equals(manifest.ownerTypeName())) {
        throw invalid(kind.sourceKind() + ".sourceOwner");
      }
      if (kind.minimumBridgeRelease() > catalog.bridgeRelease()) {
        throw invalid(kind.sourceKind() + ".minimumBridgeRelease");
      }
      if (kind.sourceSchemaVersion() != manifest.schemaVersion()) {
        throw invalid(kind.sourceKind() + ".sourceSchemaVersion");
      }
      if (!kind.keyMapping().sourcePath().equals(persistedIdField(manifest))) {
        throw invalid(kind.sourceKind() + ".keyMapping.sourcePath");
      }
      if (!kind.fieldMappings().keySet().equals(persistedFields(manifest))) {
        throw invalid(kind.sourceKind() + ".fieldMappings");
      }
      requireDeclaredTargetTables(kind);
      for (var dependency : kind.dependsOnKinds()) {
        var dependencyKind = kinds.get(dependency);
        if (dependencyKind == null || dependencyKind.loadOrder() >= kind.loadOrder()) {
          throw invalid(kind.sourceKind() + ".dependsOnKinds");
        }
      }
    }
    return catalog;
  }

  private static void requireDeclaredTargetTables(PostgresqlMigrationCatalog.Kind kind) {
    if (!kind.targetTables().contains(tableName(kind.keyMapping().targetColumn()))) {
      throw invalid(kind.sourceKind() + ".targetTables");
    }
    kind.fieldMappings().values().stream()
        .flatMap(mapping -> mapping.targets().stream())
        .map(PostgresqlMigrationCatalogValidator::tableName)
        .filter(table -> !kind.targetTables().contains(table))
        .findFirst()
        .ifPresent(table -> {
          throw invalid(kind.sourceKind() + ".targetTables");
        });
  }

  private static String tableName(String target) {
    return target.substring(0, target.indexOf('.'));
  }

  private static Set<String> persistedFields(
      DomainCollectionManifest.KindDefinition definition) {
    if (definition.kind().equals("domain_collection_cutover")) {
      return CUTOVER_FIELDS;
    }
    return persistedFields(ownerClass(definition))
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .filter(field -> !field.isSynthetic())
        .filter(field -> !field.isAnnotationPresent(Id.class))
        .map(java.lang.reflect.Field::getName)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String persistedIdField(
      DomainCollectionManifest.KindDefinition definition) {
    if (definition.kind().equals("domain_collection_cutover")) {
      return "id";
    }
    return persistedFields(ownerClass(definition))
        .filter(field -> field.isAnnotationPresent(Id.class))
        .map(java.lang.reflect.Field::getName)
        .findFirst()
        .orElseThrow(() -> invalid(definition.kind() + ".keyMapping.sourcePath"));
  }

  private static Stream<Field> persistedFields(Class<?> owner) {
    return Stream.<Class<?>>iterate(owner, type -> type != null, type -> type.getSuperclass())
        .flatMap(type -> Arrays.stream(type.getDeclaredFields()));
  }

  private static Class<?> ownerClass(DomainCollectionManifest.KindDefinition definition) {
    try {
      return Class.forName(definition.ownerTypeName());
    } catch (ClassNotFoundException failure) {
      throw new IllegalStateException(
          "Manifest owner type is unavailable for " + definition.kind() + '.', failure);
    }
  }

  private static IllegalArgumentException invalid(String path) {
    return new IllegalArgumentException("PostgreSQL migration catalog is invalid at " + path + '.');
  }
}

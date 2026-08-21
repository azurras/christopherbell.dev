package dev.christopherbell.configuration.persistence.migration;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Instantiates and verifies the exact transformer class declared by every catalog kind. */
public final class MigrationTransformerRegistry {
  private static final String INVALID = "PostgreSQL migration transformer registry is invalid.";

  private final Map<String, MigrationTransformer> byKind;

  private MigrationTransformerRegistry(Map<String, MigrationTransformer> byKind) {
    this.byKind = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byKind));
  }

  /** Binds all catalog kinds in load order and rejects missing, duplicate, or mismatched classes. */
  public static MigrationTransformerRegistry from(PostgresqlMigrationCatalog catalog) {
    var ordered = catalog.kinds().stream()
        .sorted(java.util.Comparator.comparingInt(PostgresqlMigrationCatalog.Kind::loadOrder))
        .toList();
    var result = new LinkedHashMap<String, MigrationTransformer>();
    for (var kind : ordered) {
      var transformer = instantiate(kind);
      if (!kind.sourceKind().equals(transformer.sourceKind())
          || result.put(kind.sourceKind(), transformer) != null) {
        throw invalid();
      }
    }
    return new MigrationTransformerRegistry(result);
  }

  public List<String> sourceKinds() {
    return List.copyOf(byKind.keySet());
  }

  public MigrationTransformer require(String sourceKind) {
    var transformer = byKind.get(sourceKind);
    if (transformer == null) {
      throw invalid();
    }
    return transformer;
  }

  private static MigrationTransformer instantiate(PostgresqlMigrationCatalog.Kind kind) {
    try {
      var type = Class.forName(kind.transformerClass());
      if (!MigrationTransformer.class.isAssignableFrom(type)) {
        throw invalid();
      }
      var constructor = type.getDeclaredConstructor(PostgresqlMigrationCatalog.Kind.class);
      constructor.setAccessible(true);
      return (MigrationTransformer) constructor.newInstance(kind);
    } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
        | IllegalAccessException | InvocationTargetException | LinkageError failure) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(INVALID);
  }
}

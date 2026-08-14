package dev.christopherbell.configuration.persistence.migration;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, immutable contract between consolidated Mongo kinds and relational targets. */
public record PostgresqlMigrationCatalog(int version, List<Kind> kinds) {
  private static final Pattern CANONICAL_NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern JAVA_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
  private static final Pattern JAVA_CLASS =
      Pattern.compile("[a-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+");
  private static final Pattern TARGET =
      Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");
  private static final Pattern RULE = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Set<String> TARGET_SCHEMAS = Set.of(
      "identity", "social", "communication", "federation", "music", "shared_folder",
      "mobility", "lunch", "canes", "platform");
  private static final Set<String> IDENTIFIER_TYPES = Set.of("string", "uuid-string");
  private static final Set<String> CONVERSIONS = Set.of(
      "string", "uuid-string", "enum-name", "instant-utc", "local-date",
      "year-month-first-day", "integer",
      "long", "boolean", "decimal-12-2", "decimal-20-9", "double", "byte-array",
      "record-flattened", "vin-response-flattened", "record-child", "string-list-child",
      "string-set-child", "string-map-child", "record-list-child", "constant-kind",
      "preserve-ledger");
  private static final Set<String> PRESENCE_RULES = Set.of("reject", "allow", "empty", "default");
  private static final Set<String> DELETE_BEHAVIORS = Set.of(
      "preserve", "cascade", "set-null", "restrict", "ledger-state");
  private static final Set<String> VERSION_SEMANTICS = Set.of(
      "initialize-zero", "preserve-optimistic", "preserve-revision", "none");
  private static final Set<String> EXPIRY_SEMANTICS = Set.of(
      "none", "preserve-deadline", "database-time-lease", "ttl-deadline");

  public PostgresqlMigrationCatalog {
    if (version != 1) {
      throw invalid("version");
    }
    kinds = List.copyOf(Objects.requireNonNull(kinds, "kinds"));
    if (kinds.isEmpty()) {
      throw invalid("kinds");
    }
    var sourceKinds = new HashSet<String>();
    for (var kind : kinds) {
      if (!sourceKinds.add(kind.sourceKind())) {
        throw invalid("kinds.sourceKind");
      }
    }
  }

  /** One exact source-kind transformation contract. */
  public record Kind(
      String sourceCollection,
      String sourceKind,
      int sourceSchemaVersion,
      int transformerVersion,
      String identifierType,
      String targetSchema,
      List<String> targetTables,
      int loadOrder,
      List<String> dependsOnKinds,
      KeyMapping keyMapping,
      Map<String, FieldMapping> fieldMappings,
      String deleteBehavior,
      String versionSemantics,
      String expirySemantics,
      String canonicalHash,
      List<String> reconciliation,
      List<String> portQueries,
      String transformerClass) {
    public Kind {
      requireCanonical(sourceCollection, "sourceCollection");
      requireCanonical(sourceKind, "sourceKind");
      if (sourceSchemaVersion < 1 || transformerVersion < 1) {
        throw invalid(sourceKind + ".schemaVersion");
      }
      requireAllowed(identifierType, IDENTIFIER_TYPES, sourceKind + ".identifierType");
      requireAllowed(targetSchema, TARGET_SCHEMAS, sourceKind + ".targetSchema");
      targetTables = copyCanonical(targetTables, sourceKind + ".targetTables");
      if (targetTables.isEmpty() || loadOrder < 1) {
        throw invalid(sourceKind + ".loadOrder");
      }
      dependsOnKinds = copyCanonical(dependsOnKinds, sourceKind + ".dependsOnKinds");
      keyMapping = Objects.requireNonNull(keyMapping, "keyMapping");
      fieldMappings = Collections.unmodifiableMap(
          new LinkedHashMap<>(Objects.requireNonNull(fieldMappings, "fieldMappings")));
      if (fieldMappings.isEmpty()) {
        throw invalid(sourceKind + ".fieldMappings");
      }
      fieldMappings.forEach((field, mapping) -> {
        if (!JAVA_FIELD.matcher(field).matches() || mapping == null) {
          throw invalid(sourceKind + ".fieldMappings");
        }
      });
      requireAllowed(deleteBehavior, DELETE_BEHAVIORS, sourceKind + ".deleteBehavior");
      requireAllowed(versionSemantics, VERSION_SEMANTICS, sourceKind + ".versionSemantics");
      requireAllowed(expirySemantics, EXPIRY_SEMANTICS, sourceKind + ".expirySemantics");
      if (!"sha256-rfc8785-v1".equals(canonicalHash)) {
        throw invalid(sourceKind + ".canonicalHash");
      }
      reconciliation = copyRules(reconciliation, sourceKind + ".reconciliation");
      portQueries = copyRules(portQueries, sourceKind + ".portQueries");
      if (reconciliation.isEmpty() || portQueries.isEmpty()
          || transformerClass == null || !JAVA_CLASS.matcher(transformerClass).matches()) {
        throw invalid(sourceKind + ".transformerClass");
      }
    }
  }

  /** Exact preservation rule for the source document identity. */
  public record KeyMapping(String sourcePath, String targetColumn, String preservation) {
    public KeyMapping {
      if (sourcePath == null || !JAVA_FIELD.matcher(sourcePath).matches()
          || targetColumn == null || !TARGET.matcher(targetColumn).matches()
          || !"exact".equals(preservation)) {
        throw invalid("keyMapping");
      }
    }
  }

  /** Top-level source field mapping to one or more scalar or child-row targets. */
  public record FieldMapping(
      List<String> targets, String conversion, String missing, String nullValue) {
    public FieldMapping {
      targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
      targets.forEach(target -> {
        if (!TARGET.matcher(target).matches()) {
          throw invalid("fieldMappings.targets");
        }
      });
      requireAllowed(conversion, CONVERSIONS, "fieldMappings.conversion");
      requireAllowed(missing, PRESENCE_RULES, "fieldMappings.missing");
      requireAllowed(nullValue, PRESENCE_RULES, "fieldMappings.nullValue");
      if (targets.isEmpty() && !"constant-kind".equals(conversion)) {
        throw invalid("fieldMappings.targets");
      }
    }
  }

  private static List<String> copyCanonical(List<String> values, String path) {
    var copy = List.copyOf(Objects.requireNonNull(values, path));
    copy.forEach(value -> requireCanonical(value, path));
    if (copy.stream().distinct().count() != copy.size()) {
      throw invalid(path);
    }
    return copy;
  }

  private static List<String> copyRules(List<String> values, String path) {
    var copy = List.copyOf(Objects.requireNonNull(values, path));
    if (copy.stream().anyMatch(value -> value == null || !RULE.matcher(value).matches())
        || copy.stream().distinct().count() != copy.size()) {
      throw invalid(path);
    }
    return copy;
  }

  private static void requireCanonical(String value, String path) {
    if (value == null || !CANONICAL_NAME.matcher(value).matches()) {
      throw invalid(path);
    }
  }

  private static void requireAllowed(String value, Set<String> allowed, String path) {
    if (!allowed.contains(value)) {
      throw invalid(path);
    }
  }

  private static IllegalArgumentException invalid(String path) {
    return new IllegalArgumentException("PostgreSQL migration catalog is invalid at " + path + '.');
  }
}

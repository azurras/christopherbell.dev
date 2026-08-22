package dev.christopherbell.configuration.persistence;

import java.util.Set;
import java.util.regex.Pattern;

/** Validated PostgreSQL identifiers shared by JPA mappings and native JDBC adapters. */
@PostgresPersistenceSupport
public final class PostgresqlSchemaNames {
  private static final Set<String> CANONICAL_SCHEMAS = Set.of(
      "identity",
      "social",
      "communication",
      "federation",
      "music",
      "shared_folder",
      "mobility",
      "lunch",
      "canes",
      "platform");
  private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern TEST_PREFIX = Pattern.compile("cbtest_[a-z0-9_]*_");
  private static final int MAX_IDENTIFIER_LENGTH = 63;

  private final String prefix;

  private PostgresqlSchemaNames(String prefix) {
    this.prefix = prefix;
  }

  public static PostgresqlSchemaNames production() {
    return new PostgresqlSchemaNames("");
  }

  public static PostgresqlSchemaNames testOwned(String prefix) {
    if (prefix == null || !TEST_PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("Test schema prefix must be an owned cbtest_*_ value.");
    }
    for (var schema : CANONICAL_SCHEMAS) {
      requireIdentifierLength(prefix + schema);
    }
    return new PostgresqlSchemaNames(prefix);
  }

  public static PostgresqlSchemaNames fromPhysicalSchema(String physicalSchema) {
    if (CANONICAL_SCHEMAS.contains(physicalSchema)) {
      return production();
    }
    var canonicalSchema = CANONICAL_SCHEMAS.stream()
        .filter(physicalSchema::endsWith)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown physical PostgreSQL schema."));
    return testOwned(physicalSchema.substring(0,
        physicalSchema.length() - canonicalSchema.length()));
  }

  public String schema(String canonicalSchema) {
    if (!CANONICAL_SCHEMAS.contains(canonicalSchema)) {
      throw new IllegalArgumentException("Unknown canonical PostgreSQL schema.");
    }
    return prefix + canonicalSchema;
  }

  public String qualifiedTable(String canonicalSchema, String table) {
    requireIdentifier(table);
    return quote(schema(canonicalSchema)) + "." + quote(table);
  }

  private static void requireIdentifier(String identifier) {
    if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
      throw new IllegalArgumentException("Invalid PostgreSQL identifier.");
    }
    requireIdentifierLength(identifier);
  }

  private static void requireIdentifierLength(String identifier) {
    if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
      throw new IllegalArgumentException("PostgreSQL identifier exceeds 63 characters.");
    }
  }

  private static String quote(String identifier) {
    return '"' + identifier + '"';
  }
}

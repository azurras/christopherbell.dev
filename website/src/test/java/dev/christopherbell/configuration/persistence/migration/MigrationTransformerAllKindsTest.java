package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationTransformerAllKindsTest {
  @Test
  void everyManifestKindTransformsDeterministicallyWithEveryDeclaredConversion() throws IOException {
    var catalog = loadCatalog();
    var registry = MigrationTransformerRegistry.from(catalog);

    assertThat(catalog.kinds()).hasSize(52).allSatisfy(kind -> {
      var source = new MigrationSourceDocument(
          kind.sourceKind(), kind.sourceSchemaVersion(), "id-🛰-" + kind.sourceKind(),
          representativePayload(kind));
      var first = registry.require(kind.sourceKind()).transform(source);
      var second = registry.require(kind.sourceKind()).transform(source);

      assertThat(first.sourceHash()).isEqualTo(second.sourceHash());
      assertThat(first.rows()).hasSameSizeAs(second.rows());
      for (var index = 0; index < first.rows().size(); index++) {
        assertThat(CanonicalMigrationHasher.sha256(first.rows().get(index).values()))
            .isEqualTo(CanonicalMigrationHasher.sha256(second.rows().get(index).values()));
      }
      assertThat(first.sourceHash()).matches("[0-9a-f]{64}");
      assertThat(first.rows()).isNotEmpty().allSatisfy(row -> {
        assertThat(row.targetSchema()).isEqualTo(kind.targetSchema());
        assertThat(kind.targetTables()).contains(row.targetTable());
        assertThat(row.values()).doesNotContainKey("_kind");
      });
    });
  }

  @Test
  void unknownPayloadFieldsAndSchemaDriftFailClosedWithoutEchoingValues() throws IOException {
    var kind = loadCatalog().kinds().getFirst();
    var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
    var payload = representativePayload(kind);
    payload.put("unknownSecretField", "must-not-leak");

    assertThatThrownBy(() -> transformer.transform(new MigrationSourceDocument(
        kind.sourceKind(), kind.sourceSchemaVersion(), "id", payload)))
        .isInstanceOf(MigrationTransformationException.class)
        .hasMessage("PostgreSQL migration source document is invalid.")
        .hasMessageNotContaining("must-not-leak");
    assertThatThrownBy(() -> transformer.transform(new MigrationSourceDocument(
        kind.sourceKind(), kind.sourceSchemaVersion() + 1, "id", representativePayload(kind))))
        .isInstanceOf(MigrationTransformationException.class);
  }

  private static LinkedHashMap<String, Object> representativePayload(
      PostgresqlMigrationCatalog.Kind kind) {
    var payload = new LinkedHashMap<String, Object>();
    kind.fieldMappings().entrySet().stream().sorted(Map.Entry.comparingByKey())
        .forEach(entry -> payload.put(entry.getKey(), representative(entry.getValue())));
    return payload;
  }

  private static Object representative(PostgresqlMigrationCatalog.FieldMapping mapping) {
    return switch (mapping.conversion()) {
      case "constant-kind" -> "ignored-envelope-kind";
      case "string" -> "Café 🛰";
      case "uuid-string" -> UUID.fromString("00000000-0000-0000-0000-000000000006");
      case "enum-name" -> "ACTIVE";
      case "instant-utc" -> Instant.parse("2026-08-14T00:00:00.123456789Z");
      case "local-date" -> LocalDate.parse("2026-08-14");
      case "integer" -> 7;
      case "long" -> 9_007_199_254_740_991L;
      case "boolean" -> true;
      case "decimal-12-2" -> new BigDecimal("9999999999.99");
      case "decimal-20-9" -> new BigDecimal("99999999999.123456789");
      case "double" -> 123.5d;
      case "byte-array" -> new byte[] {0, 1, -1};
      case "record-flattened", "vin-response-flattened", "record-child", "preserve-ledger" ->
          nested(mapping);
      case "record-list-child" -> List.of(nested(mapping), nested(mapping));
      case "string-list-child" -> List.of("duplicate", "duplicate", "é");
      case "string-set-child" -> new LinkedHashSet<>(List.of("first", "second"));
      case "string-map-child" -> new LinkedHashMap<>(Map.of("b", "two", "a", "one"));
      default -> throw new AssertionError(mapping.conversion());
    };
  }

  private static Map<String, Object> nested(PostgresqlMigrationCatalog.FieldMapping mapping) {
    var result = new LinkedHashMap<String, Object>();
    for (var target : mapping.targets()) {
      var column = target.substring(target.indexOf('.') + 1);
      if (column.equals("ordinal") || column.endsWith("_present")) {
        continue;
      }
      result.put(column, nestedScalar(column));
    }
    if (result.isEmpty()) {
      result.put("value", "nested");
    }
    return result;
  }

  private static Object nestedScalar(String column) {
    if (column.contains("created") || column.contains("updated")
        || column.contains("expires") || column.endsWith("_on") || column.endsWith("_at")) {
      return Instant.parse("2026-08-14T00:00:00Z");
    }
    if (column.endsWith("count") || column.endsWith("version") || column.endsWith("size")) {
      return 1L;
    }
    if (column.startsWith("is_") || column.endsWith("_enabled")) {
      return true;
    }
    return "nested-" + column;
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MigrationTransformerAllKindsTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}

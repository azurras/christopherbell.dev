package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VinMigrationTransformerTest {
  @Test
  void preservesOuterAndNestedRawValuePresenceAndSortedChildRows() throws Exception {
    var catalog = loadCatalog();
    var kind = catalog.kinds().stream()
        .filter(candidate -> candidate.sourceKind().equals("vin_decode_cache"))
        .findFirst().orElseThrow();
    var transformer = MigrationTransformerRegistry.from(catalog).require(kind.sourceKind());
    var withoutRawMap = transformer.transform(source(Map.of("vin", "VIN-1")));
    var rawValues = new LinkedHashMap<String, Object>();
    rawValues.put("B", "two");
    rawValues.put("A", "one");
    var withRawMap = transformer.transform(source(Map.of(
        "vin", "VIN-1", "rawDecodedValues", rawValues)));

    assertThat(withoutRawMap.rows().getFirst().values())
        .containsEntry("response_present", true)
        .containsEntry("raw_decoded_values_present", false);
    assertThat(withoutRawMap.rows()).hasSize(1);
    assertThat(withRawMap.rows().getFirst().values())
        .containsEntry("response_present", true)
        .containsEntry("raw_decoded_values_present", true);
    assertThat(withRawMap.rows().subList(1, 3))
        .extracting(row -> List.of(
            row.values().get("field_name"), row.values().get("field_value")))
        .containsExactly(List.of("A", "one"), List.of("B", "two"));
  }

  private static MigrationSourceDocument source(Map<String, Object> response) {
    return new MigrationSourceDocument(
        "vin_decode_cache", 1, "VIN-1", Map.of("response", response));
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws Exception {
    try (var input = VinMigrationTransformerTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}

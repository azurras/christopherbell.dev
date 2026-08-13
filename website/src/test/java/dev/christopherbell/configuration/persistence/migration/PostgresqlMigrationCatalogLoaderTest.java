package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PostgresqlMigrationCatalogLoaderTest {
  private final PostgresqlMigrationCatalogLoader loader = new PostgresqlMigrationCatalogLoader();

  @Test
  void loadsACompleteStrictKindContract() {
    var catalog = loader.load(bytes(validCatalog()));

    assertThat(catalog.version()).isEqualTo(1);
    assertThat(catalog.kinds()).hasSize(52);
    assertThat(catalog.kinds()).filteredOn(kind -> kind.sourceKind().equals("account"))
        .singleElement()
        .satisfies(kind -> {
          assertThat(kind.targetSchema()).isEqualTo("identity");
          assertThat(kind.fieldMappings()).containsKey("email").doesNotContainKey("id");
          assertThat(kind.keyMapping().targetColumn()).isEqualTo("account.account_id");
        });
  }

  @Test
  void rejectsAliasesDuplicateKeysAndUnknownPropertiesAtTheParseBoundary() {
    var alias = validCatalog()
        .replaceFirst("reconciliation: \\[", "reconciliation: &rules [")
        .replaceFirst("portQueries: \\[[^\\r\\n]+]", "portQueries: *rules");
    var duplicate = validCatalog().replace(
        "    sourceKind: account", "    sourceKind: account\n    sourceKind: duplicate");
    var unknown = validCatalog().replace(
        "version: 1", "version: 1\nunknownCatalogProperty: rejected");

    assertThatThrownBy(() -> loader.load(bytes(alias)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("alias");
    assertThatThrownBy(() -> loader.load(bytes(duplicate)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("duplicate");
    assertThatThrownBy(() -> loader.load(bytes(unknown)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("unknownCatalogProperty")
        .hasMessageNotContaining("reconciliation");
  }

  @Test
  void rejectsWildcardsAndUnrecognizedMappingRulesAfterParsing() {
    var wildcard = validCatalog().replaceFirst("(?m)^      email:", "      \"*\":");
    var conversion = validCatalog().replaceFirst(
        "conversion: string", "conversion: deserialize-anything");

    assertThatThrownBy(() -> loader.load(bytes(wildcard)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("fieldMappings");
    assertThatThrownBy(() -> loader.load(bytes(conversion)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("conversion");
  }

  @Test
  void rejectsUnknownManifestKindsFieldsAndUndeclaredTargetTables() {
    var kind = validCatalog().replaceFirst("sourceKind: account", "sourceKind: unknown_account");
    var field = validCatalog().replaceFirst("(?m)^      email:", "      inventedField:");
    var table = validCatalog().replaceFirst(
        "targetTables: \\[account,", "targetTables: [invented_table,");

    assertThatThrownBy(() -> loader.load(bytes(kind)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("kinds.sourceKind");
    assertThatThrownBy(() -> loader.load(bytes(field)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("account.fieldMappings");
    assertThatThrownBy(() -> loader.load(bytes(table)))
        .isInstanceOf(PostgresqlMigrationCatalogException.class)
        .hasMessageContaining("account.targetTables");
  }

  private static ByteArrayInputStream bytes(String yaml) {
    return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
  }

  private static String validCatalog() {
    try (var input = PostgresqlMigrationCatalogLoaderTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      if (input == null) {
        throw new IllegalStateException("Canonical migration catalog is unavailable.");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new IllegalStateException("Canonical migration catalog could not be read.", failure);
    }
  }
}

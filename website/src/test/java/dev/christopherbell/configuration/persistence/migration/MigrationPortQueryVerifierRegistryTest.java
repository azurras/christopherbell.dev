package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class MigrationPortQueryVerifierRegistryTest {
  @Test
  void executableRegistryNamesExactlyCoverEveryDeclaredCatalogPortQuery() throws IOException {
    var catalog = loadCatalog();
    var declared = new LinkedHashSet<String>();
    catalog.kinds().forEach(kind -> declared.addAll(kind.portQueries()));

    var registry = MigrationPortQueryVerifierRegistry.from(catalog);

    assertThat(registry.names()).containsExactlyInAnyOrderElementsOf(declared);
    assertThat(registry.names()).hasSize(82);
    assertThat(registry.declarationCount()).isEqualTo(153);
    assertThat(catalog.kinds().stream().mapToInt(kind -> kind.portQueries().size()).sum())
        .isEqualTo(153);
    assertThat(registry.semanticFamily("post", "author-feed-page"))
        .isEqualTo("KEYSET_PAGE");
    assertThat(registry.semanticFamily("application_lease", "claim-expired-lease"))
        .isEqualTo("CONDITIONAL_CLAIM");
    assertThat(registry.semanticFamily("message", "participant-page"))
        .isEqualTo("JOINED_CHILD_PAGE");
    assertThat(registry.semanticFamily("music_track", "artist-page"))
        .isEqualTo("GROUPED_PROJECTION");
    assertThat(registry.nullPlacement("account_follow", "follower-page", "created_on"))
        .isEqualTo("FIRST");
    assertThat(registry.nullPlacement(
        "federation_delivery_job", "due-job-page", "next_attempt_on"))
        .isEqualTo("FIRST");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void everyExplicitDeclarationReferencesRealTypedColumns() throws Exception {
    var catalog = loadCatalog();
    var registry = MigrationPortQueryVerifierRegistry.from(catalog);
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      assertThat(registry.schemaViolations(connection, database.prefix(), catalog)).isEmpty();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void conditionalLeaseStrategiesUseDatabaseTimeAndRollbackEveryProbe() throws Exception {
    var registry = MigrationPortQueryVerifierRegistry.from(loadCatalog());
    try (var database = PostgresqlSchemaTestSupport.migrate();
         var connection = database.connect()) {
      connection.setAutoCommit(false);
      assertThat(registry.verifyConditionalClaimForTest(
          connection, database.prefix() + "platform", "application_lease")).isTrue();
      assertThat(registry.verifyConditionalClaimForTest(
          connection, database.prefix() + "shared_folder", "maintenance_lease")).isTrue();
      assertThat(count(connection, database.prefix() + "platform", "application_lease"))
          .isZero();
      assertThat(count(connection, database.prefix() + "shared_folder", "maintenance_lease"))
          .isZero();
      connection.rollback();
    }
  }

  private static long count(java.sql.Connection connection, String schema, String table)
      throws java.sql.SQLException {
    try (var statement = connection.createStatement();
         var rows = statement.executeQuery(
             "select count(*) from \"" + schema + "\".\"" + table + "\"")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MigrationPortQueryVerifierRegistryTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}

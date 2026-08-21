package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class ProductionPostgresqlSchemaMigratorTest {

  @Test
  void productionSettingsRequireExactLoopbackDatabaseAndMigratorWithoutLeakingSecrets() {
    var environment = Map.of(
        "SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/christopherbell",
        "SPRING_DATASOURCE_USERNAME", "christopherbell_migrator",
        "SPRING_DATASOURCE_PASSWORD", "migration-secret-value");

    var settings = ProductionPostgresqlSchemaMigrator.Settings.production(environment);

    assertThat(settings.toString()).doesNotContain("migration-secret-value");
    assertThatThrownBy(() -> ProductionPostgresqlSchemaMigrator.Settings.production(Map.of(
        "SPRING_DATASOURCE_URL", "jdbc:postgresql://db.example.com:5432/christopherbell",
        "SPRING_DATASOURCE_USERNAME", "christopherbell_migrator",
        "SPRING_DATASOURCE_PASSWORD", "migration-secret-value")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("db.example.com");
    assertThatThrownBy(() -> ProductionPostgresqlSchemaMigrator.Settings.production(Map.of(
        "SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/christopherbell",
        "SPRING_DATASOURCE_USERNAME", "christopherbell_app",
        "SPRING_DATASOURCE_PASSWORD", "migration-secret-value")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("christopherbell_app");
  }

  @Test
  void runtimeGrantSqlCoversEveryCanonicalSchemaAndPreservesRoleSeparation() {
    var sql = ProductionPostgresqlSchemaMigrator.runtimeGrantSql("");

    for (var schema : new String[]{"identity", "social", "communication", "federation",
        "music", "shared_folder", "mobility", "lunch", "canes", "platform"}) {
      assertThat(sql).contains("\"" + schema + "\"");
    }
    assertThat(sql)
        .contains("christopherbell_app")
        .contains("christopherbell_bridge")
        .contains("christopherbell_viewer")
        .contains("christopherbell_backup")
        .contains("GRANT SELECT ON TABLE \"flyway_schema_history\" TO "
            + "christopherbell_app, christopherbell_bridge, "
            + "christopherbell_viewer, christopherbell_backup")
        .doesNotContain("PASSWORD");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void migrationRunsThroughV27AndAppliesLeastPrivilegeRuntimeGrants() throws Exception {
    var url = requiredEnvironment("SPRING_DATASOURCE_URL");
    var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
    var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
    var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    var prefix = "cbtest_t7migrator_" + suffix + "_";
    var roles = new ProductionPostgresqlSchemaMigrator.RuntimeRoles(
        username, "pg_write_all_data", "pg_write_all_data",
        "pg_read_all_data", "pg_read_all_data");
    var port = URI.create(url.substring("jdbc:".length())).getPort();
    try (var connection = DriverManager.getConnection(url, username, password);
         var statement = connection.createStatement()) {
      try {
        ProductionPostgresqlSchemaMigrator.migrate(
            new ProductionPostgresqlSchemaMigrator.Settings(url, username, password),
            prefix, "test", username, port, roles);

        assertThat(scalar(connection,
            "select version::text from public.\"flyway_" + prefix + "history\" "
                + "order by installed_rank desc limit 1")).isEqualTo("27");
        var account = "\"" + prefix + "identity\".\"account\"";
        for (var privilege : new String[]{"SELECT", "INSERT", "UPDATE", "DELETE"}) {
          assertThat(booleanScalar(connection,
              "select has_table_privilege('" + roles.app() + "', '" + account
                  + "', '" + privilege + "')")).isTrue();
        }
        assertThat(booleanScalar(connection,
            "select has_table_privilege('" + roles.viewer() + "', '" + account
                + "', 'SELECT')")).isTrue();
        assertThat(booleanScalar(connection,
            "select has_table_privilege('" + roles.viewer() + "', '" + account
                + "', 'INSERT')")).isFalse();
        assertThat(booleanScalar(connection,
            "select has_schema_privilege('" + roles.app() + "', '" + prefix
                + "identity', 'CREATE')")).isFalse();
      } finally {
        statement.execute("drop table if exists public.\"flyway_" + prefix + "history\"");
        for (var schema : new String[]{"platform", "canes", "lunch", "mobility",
            "shared_folder", "music", "federation", "communication", "social", "identity"}) {
          statement.execute("drop schema if exists \"" + prefix + schema + "\" cascade");
        }
      }
    }
  }

  private static String scalar(java.sql.Connection connection, String sql) throws Exception {
    try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }

  private static boolean booleanScalar(java.sql.Connection connection, String sql)
      throws Exception {
    try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private static String requiredEnvironment(String name) {
    var value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required for PostgreSQL integration tests.");
    }
    return value;
  }
}

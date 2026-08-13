package dev.christopherbell.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class PostgresqlJooqSchemaToolTest {

  @TempDir Path temporaryDirectory;

  @Test
  void acceptsOnlyPrefixesWhoseLongestSchemaIdentifierCannotBeTruncated() {
    assertThatCode(() -> PostgresqlJooqSchemaTool.requireOwnedPrefix("cbtest_safe_run_"))
        .doesNotThrowAnyException();

    var truncatingPrefix = "cbtest_" + "a".repeat(56) + '_';
    assertThatThrownBy(() -> PostgresqlJooqSchemaTool.requireOwnedPrefix(truncatingPrefix))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("63");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
  void cleanupRequiresThePersistedRunOwnershipMarker() throws Exception {
    var prefix = "cbtest_c_" + UUID.randomUUID().toString().replace("-", "") + '_';
    var environment = Map.of(
        "JOOQ_CODEGEN_JDBC_URL", required("SPRING_DATASOURCE_URL"),
        "JOOQ_CODEGEN_USERNAME", required("SPRING_DATASOURCE_USERNAME"),
        "JOOQ_CODEGEN_PASSWORD", required("SPRING_DATASOURCE_PASSWORD"),
        "JOOQ_CODEGEN_SCHEMA", prefix);

    PostgresqlJooqSchemaTool.run("prepare", environment, temporaryDirectory);
    var ownershipFile = onlyOwnershipFile();
    var originalToken = Files.readString(ownershipFile, StandardCharsets.US_ASCII);
    try {
      Files.writeString(
          ownershipFile, UUID.randomUUID().toString(), StandardCharsets.US_ASCII);
      assertThatThrownBy(() -> PostgresqlJooqSchemaTool.run(
          "clean", environment, temporaryDirectory))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ownership marker");
      assertThat(schemaExists(environment, prefix + "identity")).isTrue();

      Files.writeString(ownershipFile, originalToken, StandardCharsets.US_ASCII);
      PostgresqlJooqSchemaTool.run("clean", environment, temporaryDirectory);
      assertThat(schemaExists(environment, prefix + "identity")).isFalse();
    } finally {
      if (Files.exists(ownershipFile) && schemaExists(environment, prefix + "platform")) {
        Files.writeString(ownershipFile, originalToken, StandardCharsets.US_ASCII);
        PostgresqlJooqSchemaTool.run("clean", environment, temporaryDirectory);
      }
    }
  }

  private Path onlyOwnershipFile() throws Exception {
    try (var paths = Files.list(temporaryDirectory)) {
      return paths.filter(Files::isRegularFile).findFirst().orElseThrow();
    }
  }

  private static boolean schemaExists(Map<String, String> environment, String schema)
      throws Exception {
    try (var connection = DriverManager.getConnection(
             environment.get("JOOQ_CODEGEN_JDBC_URL"),
             environment.get("JOOQ_CODEGEN_USERNAME"),
             environment.get("JOOQ_CODEGEN_PASSWORD"));
         var statement = connection.prepareStatement(
             "select count(*) from information_schema.schemata where schema_name = ?")) {
      statement.setString(1, schema);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1) == 1;
      }
    }
  }

  private static String required(String name) {
    var value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be nonblank.");
    }
    return value;
  }
}

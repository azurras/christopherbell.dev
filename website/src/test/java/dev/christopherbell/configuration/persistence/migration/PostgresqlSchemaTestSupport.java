package dev.christopherbell.configuration.persistence.migration;

import dev.christopherbell.configuration.persistence.PostgresqlDatabaseIdentity;
import dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuard;
import dev.christopherbell.configuration.persistence.PostgresqlTestDatabaseGuardProperties;
import dev.christopherbell.configuration.persistence.PostgresqlTestSchemaName;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;

final class PostgresqlSchemaTestSupport {
  static final List<String> DOMAINS = List.of(
      "identity", "social", "communication", "federation", "music", "shared_folder",
      "mobility", "lunch", "canes", "platform");
  private static final PostgresqlTestDatabaseGuardProperties GUARD =
      new PostgresqlTestDatabaseGuardProperties("test", "cbtest_");
  private static final Pattern OWNED_PREFIX = Pattern.compile("cbtest_t2_[a-z0-9_]+_");

  private PostgresqlSchemaTestSupport() {}

  static MigratedDatabase migrate() throws SQLException {
    return migrateThrough(null);
  }

  static MigratedDatabase migrateThrough(String targetVersion) throws SQLException {
    var prefix = PostgresqlTestSchemaName.create("cbtest_t2_").value() + '_';
    if (!OWNED_PREFIX.matcher(prefix).matches()
        || DOMAINS.stream().anyMatch(domain -> prefix.length() + domain.length() > 63)) {
      throw new IllegalStateException("Generated PostgreSQL Task 2 schema prefix is invalid.");
    }
    var url = requiredEnvironment("SPRING_DATASOURCE_URL");
    var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
    var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
    requireSafeDatabase(url, username, password, prefix);
    System.out.println("Task 2 PostgreSQL schema prefix: " + prefix);
    try {
      var configuration = Flyway.configure()
          .dataSource(url, username, password)
          .locations("classpath:db/migration")
          .schemas("public")
          .defaultSchema("public")
          .table(historyTable(prefix))
          .createSchemas(false)
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .baselineDescription("Task 2 isolated schema-history bootstrap")
          .cleanDisabled(true)
          .placeholders(Map.of("schema_prefix", prefix))
          .validateMigrationNaming(true);
      if (targetVersion != null) {
        configuration.target(targetVersion);
      }
      var result = configuration.load()
          .migrate();
      return new MigratedDatabase(
          url, username, password, prefix, result.migrationsExecuted);
    } catch (RuntimeException failure) {
      dropOwnedSchemas(url, username, password, prefix);
      throw failure;
    }
  }

  private static void requireSafeDatabase(
      String url, String username, String password, String prefix) throws SQLException {
    try (var connection = DriverManager.getConnection(url, username, password);
         var statement = connection.createStatement();
         var result = statement.executeQuery("select current_database()")) {
      result.next();
      PostgresqlTestDatabaseGuard.requireSafeIdentity(
          new PostgresqlDatabaseIdentity(result.getString(1), prefix + "identity"), GUARD);
    }
  }

  private static void dropOwnedSchemas(
      String url, String username, String password, String prefix) {
    if (!OWNED_PREFIX.matcher(prefix).matches()) {
      throw new IllegalStateException("Refusing to drop unowned PostgreSQL schemas.");
    }
    try (var connection = DriverManager.getConnection(url, username, password)) {
      requireSafeDatabase(url, username, password, prefix);
      try (var statement = connection.createStatement()) {
        statement.execute("drop table if exists public.\"" + historyTable(prefix) + "\"");
      }
      for (var index = DOMAINS.size() - 2; index >= 0; index--) {
        dropSchema(connection, prefix + DOMAINS.get(index));
      }
      dropSchema(connection, prefix + "platform");
    } catch (SQLException failure) {
      throw new IllegalStateException("Owned PostgreSQL test schemas could not be removed.", failure);
    }
  }

  private static void dropSchema(Connection connection, String schema) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("drop schema if exists \"" + schema + "\" cascade");
    }
  }

  private static String historyTable(String prefix) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = HexFormat.of().formatHex(
          digest.digest(prefix.getBytes(StandardCharsets.US_ASCII)));
      return "flyway_cbtest_" + hash.substring(0, 24) + "_history";
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static String requiredEnvironment(String key) {
    var value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(key + " must be set for PostgreSQL integration tests.");
    }
    return value;
  }

  static final class MigratedDatabase implements AutoCloseable {
    private final String url;
    private final String username;
    private final String password;
    private final String prefix;
    private final int migrationsExecuted;

    MigratedDatabase(
        String url, String username, String password, String prefix, int migrationsExecuted) {
      this.url = url;
      this.username = username;
      this.password = password;
      this.prefix = prefix;
      this.migrationsExecuted = migrationsExecuted;
    }

    String prefix() {
      return prefix;
    }

    int migrationsExecuted() {
      return migrationsExecuted;
    }

    Connection connect() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    JdbcConfiguration jdbcConfiguration() {
      return new JdbcConfiguration(url, username, password);
    }

    int migrateToLatest() {
      return Flyway.configure()
          .dataSource(url, username, password)
          .locations("classpath:db/migration")
          .schemas("public")
          .defaultSchema("public")
          .table(historyTable(prefix))
          .createSchemas(false)
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .baselineDescription("Task 2 isolated schema-history bootstrap")
          .cleanDisabled(true)
          .placeholders(Map.of("schema_prefix", prefix))
          .validateMigrationNaming(true)
          .load()
          .migrate()
          .migrationsExecuted;
    }

    @Override
    public void close() {
      dropOwnedSchemas(url, username, password, prefix);
    }
  }

  record JdbcConfiguration(String url, String username, String password) {}
}

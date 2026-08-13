package dev.christopherbell.configuration.postgresql;

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
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/** Owns one isolated PostgreSQL schema set for Task 3 adapter integration tests. */
public final class Task3PostgresqlTestSupport implements AutoCloseable {
  private static final List<String> DOMAINS = List.of(
      "identity", "social", "communication", "federation", "music", "shared_folder",
      "mobility", "lunch", "canes", "platform");
  private static final Pattern OWNED_PREFIX = Pattern.compile("cbtest_t3_[a-z0-9_]+_");
  private static final PostgresqlTestDatabaseGuardProperties GUARD =
      new PostgresqlTestDatabaseGuardProperties("test", "cbtest_");

  private final String url;
  private final String username;
  private final String password;
  private final String prefix;

  private Task3PostgresqlTestSupport(
      String url, String username, String password, String prefix) {
    this.url = url;
    this.username = username;
    this.password = password;
    this.prefix = prefix;
  }

  public static Task3PostgresqlTestSupport migrate() throws SQLException {
    var prefix = PostgresqlTestSchemaName.create("cbtest_t3_").value() + '_';
    requireOwnedPrefix(prefix);
    var url = requiredEnvironment("SPRING_DATASOURCE_URL");
    var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
    var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
    requireSafeDatabase(url, username, password, prefix);
    System.out.println("Task 3 PostgreSQL database: test");
    System.out.println("Task 3 PostgreSQL owned schemas: "
        + DOMAINS.stream().map(prefix::concat).toList());
    try {
      Flyway.configure()
          .dataSource(url, username, password)
          .locations("classpath:db/migration")
          .schemas("public")
          .defaultSchema("public")
          .table(historyTable(prefix))
          .createSchemas(false)
          .baselineOnMigrate(true)
          .baselineVersion("0")
          .baselineDescription("Task 3 isolated schema-history bootstrap")
          .cleanDisabled(true)
          .placeholders(Map.of("schema_prefix", prefix))
          .validateMigrationNaming(true)
          .load()
          .migrate();
      return new Task3PostgresqlTestSupport(url, username, password, prefix);
    } catch (RuntimeException failure) {
      dropOwnedSchemas(url, username, password, prefix);
      throw failure;
    }
  }

  public Database openDatabase() throws SQLException {
    var connection = DriverManager.getConnection(url, username, password);
    var mappedSchemas = DOMAINS.stream()
        .map(domain -> new MappedSchema().withInput(domain).withOutput(prefix + domain))
        .toList();
    var settings = new Settings().withRenderMapping(
        new RenderMapping().withSchemata(mappedSchemas));
    return new Database(connection, DSL.using(connection, SQLDialect.POSTGRES, settings));
  }

  public String prefix() {
    return prefix;
  }

  @Override
  public void close() {
    dropOwnedSchemas(url, username, password, prefix);
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
    requireOwnedPrefix(prefix);
    try (var connection = DriverManager.getConnection(url, username, password)) {
      requireSafeDatabase(url, username, password, prefix);
      try (var statement = connection.createStatement()) {
        statement.execute("drop table if exists public.\"" + historyTable(prefix) + "\"");
        for (var index = DOMAINS.size() - 1; index >= 0; index--) {
          statement.execute("drop schema if exists \"" + prefix + DOMAINS.get(index)
              + "\" cascade");
        }
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("Owned PostgreSQL Task 3 schemas could not be removed.", failure);
    }
  }

  private static void requireOwnedPrefix(String prefix) {
    if (!OWNED_PREFIX.matcher(prefix).matches()
        || DOMAINS.stream().anyMatch(domain -> prefix.length() + domain.length() > 63)) {
      throw new IllegalStateException("Refusing to use an unowned PostgreSQL Task 3 schema prefix.");
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

  public record Database(Connection connection, DSLContext dsl) implements AutoCloseable {
    @Override
    public void close() throws SQLException {
      connection.close();
    }
  }
}

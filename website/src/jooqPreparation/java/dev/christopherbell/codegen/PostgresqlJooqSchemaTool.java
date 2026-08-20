package dev.christopherbell.codegen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;

/** Guarded Flyway preparation and exact-prefix cleanup for reproducible jOOQ generation. */
public final class PostgresqlJooqSchemaTool {
  private static final List<String> DOMAINS = List.of(
      "identity", "social", "communication", "federation", "music", "shared_folder",
      "mobility", "lunch", "canes", "platform");
  private static final Pattern OWNED_PREFIX = Pattern.compile("cbtest_[a-z0-9_]+_");
  private static final int POSTGRESQL_IDENTIFIER_LIMIT = 63;
  private static final String LONGEST_DOMAIN = "communication";
  private static final String MARKER_CATALOG_VERSION = "jooq-codegen-3.21.5";

  private PostgresqlJooqSchemaTool() {}

  public static void main(String[] arguments) throws SQLException, IOException {
    if (arguments.length != 2
        || !(arguments[0].equals("prepare") || arguments[0].equals("clean"))) {
      throw new IllegalArgumentException(
          "Expected mode prepare or clean followed by the ownership directory.");
    }
    run(arguments[0], System.getenv(), Path.of(arguments[1]));
  }

  static void run(String mode, Map<String, String> environment, Path ownershipDirectory)
      throws SQLException, IOException {
    if (!(mode.equals("prepare") || mode.equals("clean"))) {
      throw new IllegalArgumentException("Expected mode prepare or clean.");
    }
    var configuration = Configuration.from(environment, ownershipDirectory);
    if (mode.equals("prepare")) {
      configuration.prepare();
    } else {
      configuration.clean();
    }
  }

  static void requireOwnedPrefix(String prefix) {
    if (!OWNED_PREFIX.matcher(prefix).matches()) {
      throw new IllegalStateException(
          "JOOQ_CODEGEN_SCHEMA_PREFIX must be an owned cbtest_<run>_ prefix.");
    }
    if (prefix.length() + LONGEST_DOMAIN.length() > POSTGRESQL_IDENTIFIER_LIMIT) {
      throw new IllegalStateException(
          "JOOQ_CODEGEN_SCHEMA_PREFIX must keep every full schema identifier within 63 bytes.");
    }
  }

  private record Configuration(
      String url, String username, String password, String prefix, Path ownershipDirectory) {
    static Configuration from(Map<String, String> environment, Path ownershipDirectory) {
      var prefix = required(environment, "JOOQ_CODEGEN_SCHEMA_PREFIX");
      requireOwnedPrefix(prefix);
      return new Configuration(
          required(environment, "JOOQ_CODEGEN_JDBC_URL"),
          required(environment, "JOOQ_CODEGEN_USERNAME"),
          required(environment, "JOOQ_CODEGEN_PASSWORD"),
          prefix,
          ownershipDirectory.toAbsolutePath().normalize());
    }

    void prepare() throws SQLException, IOException {
      var ownerToken = UUID.randomUUID();
      var ownershipFile = ownershipFile();
      Files.createDirectories(ownershipDirectory);
      Files.writeString(
          ownershipFile,
          ownerToken.toString(),
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);

      var cleanTargetClaimed = false;
      var prepared = false;
      try (var lockConnection = connect()) {
        requireSafeIdentity(lockConnection);
        requireExclusivePrefixLock(lockConnection);
        requireSchemasAbsent(lockConnection);
        cleanTargetClaimed = true;
        try {
          var result = flyway().migrate();
          if (result.migrationsExecuted != 14) {
            throw new IllegalStateException("Expected exactly fourteen canonical Flyway migrations.");
          }
          insertOwnershipMarker(lockConnection, ownerToken);
          prepared = true;
        } finally {
          if (!prepared && cleanTargetClaimed) {
            dropOwnedObjects(lockConnection);
          }
        }
      } finally {
        if (!prepared) {
          Files.deleteIfExists(ownershipFile);
        }
      }
      System.out.println("Prepared jOOQ schemas in database test with prefix " + prefix + '.');
    }

    void clean() throws SQLException, IOException {
      var ownershipFile = ownershipFile();
      var ownerToken = readOwnerToken(ownershipFile);
      try (var lockConnection = connect()) {
        requireSafeIdentity(lockConnection);
        requireExclusivePrefixLock(lockConnection);
        requireOwnershipMarker(lockConnection, ownerToken);
        dropOwnedObjects(lockConnection);
      }
      Files.delete(ownershipFile);
      System.out.println("Removed exact owned jOOQ schemas with prefix " + prefix + '.');
    }

    private Flyway flyway() {
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
          .load();
    }

    private Connection connect() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    private void requireSafeIdentity(Connection connection) throws SQLException {
      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("select current_database()")) {
        if (!rows.next() || !"test".equals(rows.getString(1))) {
          throw new IllegalStateException("jOOQ generation is allowed only in database test.");
        }
      }
    }

    private void requireExclusivePrefixLock(Connection connection) throws SQLException {
      try (var statement = connection.prepareStatement(
          "select pg_try_advisory_lock(hashtextextended(?, 0))")) {
        statement.setString(1, prefix);
        try (var rows = statement.executeQuery()) {
          rows.next();
          if (!rows.getBoolean(1)) {
            throw new IllegalStateException("The jOOQ schema prefix is already owned by another run.");
          }
        }
      }
    }

    private void requireSchemasAbsent(Connection connection) throws SQLException {
      try (var statement = connection.prepareStatement(
          "select count(*) from information_schema.schemata where schema_name = any (?)")) {
        var schemaNames = DOMAINS.stream().map(prefix::concat).toArray(String[]::new);
        statement.setArray(1, connection.createArrayOf("text", schemaNames));
        try (var rows = statement.executeQuery()) {
          rows.next();
          if (rows.getInt(1) != 0) {
            throw new IllegalStateException("jOOQ generation requires a clean unique schema prefix.");
          }
        }
      }
      try (var statement = connection.prepareStatement(
          "select count(*) from information_schema.tables "
              + "where table_schema = 'public' and table_name = ?")) {
        statement.setString(1, historyTable(prefix));
        try (var rows = statement.executeQuery()) {
          rows.next();
          if (rows.getInt(1) != 0) {
            throw new IllegalStateException("jOOQ generation requires a clean unique history table.");
          }
        }
      }
    }

    private void insertOwnershipMarker(Connection connection, UUID ownerToken) throws SQLException {
      var sql = "insert into \"" + prefix + "platform\".persistence_migration_run "
          + "(run_id, catalog_version, source_database, target_database, source_frozen, status) "
          + "values (?, ?, 'jooq-generation', 'test', false, 'STAGING')";
      try (var statement = connection.prepareStatement(sql)) {
        statement.setObject(1, ownerToken);
        statement.setString(2, MARKER_CATALOG_VERSION);
        statement.executeUpdate();
      }
    }

    private void requireOwnershipMarker(Connection connection, UUID ownerToken) throws SQLException {
      var sql = "select count(*) from \"" + prefix + "platform\".persistence_migration_run "
          + "where run_id = ? and catalog_version = ? and target_database = 'test'";
      try (var statement = connection.prepareStatement(sql)) {
        statement.setObject(1, ownerToken);
        statement.setString(2, MARKER_CATALOG_VERSION);
        try (var rows = statement.executeQuery()) {
          rows.next();
          if (rows.getInt(1) != 1) {
            throw new IllegalStateException(
                "Refusing cleanup because the jOOQ schema ownership marker does not match.");
          }
        }
      }
    }

    private void dropOwnedObjects(Connection connection) throws SQLException {
      try (var statement = connection.createStatement()) {
        statement.execute("drop table if exists public.\"" + historyTable(prefix) + "\"");
      }
      for (var index = DOMAINS.size() - 2; index >= 0; index--) {
        dropSchema(connection, prefix + DOMAINS.get(index));
      }
      dropSchema(connection, prefix + "platform");
    }

    private Path ownershipFile() {
      return ownershipDirectory.resolve(sha256(prefix) + ".token");
    }
  }

  private static void dropSchema(Connection connection, String schema) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("drop schema if exists \"" + schema + "\" cascade");
    }
  }

  private static UUID readOwnerToken(Path ownershipFile) throws IOException {
    if (!Files.isRegularFile(ownershipFile)) {
      throw new IllegalStateException(
          "Refusing cleanup because the jOOQ schema ownership token is missing.");
    }
    try {
      return UUID.fromString(Files.readString(ownershipFile, StandardCharsets.US_ASCII).trim());
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException(
          "Refusing cleanup because the jOOQ schema ownership token is invalid.", failure);
    }
  }

  private static String historyTable(String prefix) {
    return "flyway_cbtest_" + sha256(prefix).substring(0, 24) + "_history";
  }

  private static String sha256(String value) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static String required(Map<String, String> environment, String name) {
    var value = environment.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " must be nonblank.");
    }
    return value;
  }
}

package dev.christopherbell.configuration.persistence.migration;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;

/** Runs the production Flyway schema transition without constructing the website application. */
public final class ProductionPostgresqlSchemaMigrator {
  private static final String PRODUCTION_URL =
      "jdbc:postgresql://127.0.0.1:5432/christopherbell";
  private static final String MIGRATOR_ROLE = "christopherbell_migrator";
  private static final String OWNER_ROLE = "christopherbell_owner";
  private static final RuntimeRoles PRODUCTION_ROLES = new RuntimeRoles(
      OWNER_ROLE, "christopherbell_app", "christopherbell_bridge",
      "christopherbell_viewer", "christopherbell_backup");
  private static final Pattern SAFE_PREFIX = Pattern.compile("[a-z0-9_]*");
  private static final List<String> CANONICAL_SCHEMAS = List.of(
      "identity", "social", "communication", "federation", "music", "shared_folder",
      "mobility", "lunch", "canes", "platform");

  private ProductionPostgresqlSchemaMigrator() {}

  /** Validates production identity, migrates through V27, applies runtime grants, and exits. */
  public static void main(String[] arguments) throws SQLException {
    if (arguments.length != 0) {
      throw new IllegalArgumentException("The production schema migrator accepts no arguments.");
    }
    var settings = Settings.production(System.getenv());
    migrate(settings, "", "christopherbell", MIGRATOR_ROLE, 5432, PRODUCTION_ROLES);
    System.out.println("postgresql-schema-migration:success");
  }

  static void migrate(
      Settings settings,
      String schemaPrefix,
      String expectedDatabase,
      String expectedRole,
      int expectedPort,
      RuntimeRoles roles) throws SQLException {
    requireSafeIdentifier(expectedDatabase, "database");
    requireSafeIdentifier(expectedRole, "role");
    Objects.requireNonNull(roles, "roles");
    roles.validate();
    if (expectedPort < 1 || expectedPort > 65_535) {
      throw new IllegalArgumentException("The PostgreSQL port identity is invalid.");
    }
    if (!SAFE_PREFIX.matcher(schemaPrefix).matches()) {
      throw new IllegalArgumentException("The schema prefix is invalid.");
    }
    verifyIdentity(settings, expectedDatabase, expectedRole, expectedPort);
    var flyway = Flyway.configure()
        .dataSource(settings.jdbcUrl(), settings.username(), settings.password())
        .locations("classpath:db/migration")
        .placeholders(Map.of("schema_prefix", schemaPrefix))
        .table(schemaPrefix.isEmpty()
            ? "flyway_schema_history"
            : "flyway_" + schemaPrefix + "history")
        .initSql("SET ROLE " + roles.owner())
        .load();
    flyway.migrate();
    var current = flyway.info().current();
    if (current == null || !"27".equals(current.getVersion().toString())) {
      throw new IllegalStateException("The production schema did not reach the required version.");
    }
    try (var connection = DriverManager.getConnection(
        settings.jdbcUrl(), settings.username(), settings.password());
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute("SET ROLE " + roles.owner());
        statement.execute(runtimeGrantSql(schemaPrefix, roles));
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static void verifyIdentity(
      Settings settings, String expectedDatabase, String expectedRole, int expectedPort)
      throws SQLException {
    try (var connection = DriverManager.getConnection(
        settings.jdbcUrl(), settings.username(), settings.password());
        var statement = connection.createStatement();
        var rows = statement.executeQuery(
            "select current_database(), current_user, current_setting('server_version'), "
                + "host(inet_server_addr()), inet_server_port()")) {
      if (!rows.next()
          || !expectedDatabase.equals(rows.getString(1))
          || !expectedRole.equals(rows.getString(2))
          || !rows.getString(3).startsWith("18.4")
          || !"127.0.0.1".equals(rows.getString(4))
          || rows.getInt(5) != expectedPort
          || rows.next()) {
        throw new IllegalStateException("The PostgreSQL migration identity is not authorized.");
      }
    }
  }

  static String runtimeGrantSql(String schemaPrefix) {
    return runtimeGrantSql(schemaPrefix, PRODUCTION_ROLES);
  }

  static String runtimeGrantSql(String schemaPrefix, RuntimeRoles roles) {
    if (!SAFE_PREFIX.matcher(schemaPrefix).matches()) {
      throw new IllegalArgumentException("The schema prefix is invalid.");
    }
    Objects.requireNonNull(roles, "roles");
    roles.validate();
    var sql = new StringBuilder();
    for (var canonicalSchema : CANONICAL_SCHEMAS) {
      var schema = quoteIdentifier(schemaPrefix + canonicalSchema);
      sql.append("GRANT USAGE ON SCHEMA ").append(schema)
          .append(" TO ").append(roles.allRuntimeSql()).append(';')
          .append("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ")
          .append(schema).append(" TO ").append(roles.writersSql()).append(';')
          .append("GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA ")
          .append(schema).append(" TO ").append(roles.writersSql()).append(';')
          .append("GRANT SELECT ON ALL TABLES IN SCHEMA ").append(schema)
          .append(" TO ").append(roles.readersSql()).append(';')
          .append("ALTER DEFAULT PRIVILEGES FOR ROLE ").append(roles.owner())
          .append(" IN SCHEMA ")
          .append(schema)
          .append(" GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ")
          .append(roles.writersSql()).append(';')
          .append("ALTER DEFAULT PRIVILEGES FOR ROLE ").append(roles.owner())
          .append(" IN SCHEMA ")
          .append(schema)
          .append(" GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO ")
          .append(roles.writersSql()).append(';')
          .append("ALTER DEFAULT PRIVILEGES FOR ROLE ").append(roles.owner())
          .append(" IN SCHEMA ")
          .append(schema)
          .append(" GRANT SELECT ON TABLES TO ").append(roles.readersSql()).append(';');
    }
    var historyTable = schemaPrefix.isEmpty()
        ? "flyway_schema_history"
        : "flyway_" + schemaPrefix + "history";
    sql.append("GRANT SELECT ON TABLE ").append(quoteIdentifier(historyTable))
        .append(" TO ").append(roles.allRuntimeSql()).append(';');
    return sql.toString();
  }

  private static String quoteIdentifier(String identifier) {
    if (!SAFE_PREFIX.matcher(identifier).matches() || identifier.isEmpty()) {
      throw new IllegalArgumentException("A PostgreSQL identifier is invalid.");
    }
    return '"' + identifier + '"';
  }

  private static void requireSafeIdentifier(String value, String category) {
    if (value == null || value.isEmpty() || !SAFE_PREFIX.matcher(value).matches()) {
      throw new IllegalArgumentException("The PostgreSQL " + category + " identity is invalid.");
    }
  }

  record Settings(String jdbcUrl, String username, String password) {
    Settings {
      Objects.requireNonNull(jdbcUrl, "jdbcUrl");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(password, "password");
    }

    static Settings production(Map<String, String> environment) {
      Objects.requireNonNull(environment, "environment");
      var url = environment.get("SPRING_DATASOURCE_URL");
      var username = environment.get("SPRING_DATASOURCE_USERNAME");
      var password = environment.get("SPRING_DATASOURCE_PASSWORD");
      if (!PRODUCTION_URL.equals(url)) {
        throw new IllegalArgumentException("The production JDBC identity is invalid.");
      }
      if (!MIGRATOR_ROLE.equals(username)) {
        throw new IllegalArgumentException("The production migration role is invalid.");
      }
      if (password == null || password.isBlank() || password.length() < 16
          || password.regionMatches(true, 0, "replace", 0, "replace".length())) {
        throw new IllegalArgumentException("The production migration secret is invalid.");
      }
      return new Settings(url, username, password);
    }

    @Override
    public String toString() {
      return "Settings[validatedProductionIdentity]";
    }
  }

  record RuntimeRoles(String owner, String app, String bridge, String viewer, String backup) {
    void validate() {
      requireSafeIdentifier(owner, "owner role");
      requireSafeIdentifier(app, "application role");
      requireSafeIdentifier(bridge, "bridge role");
      requireSafeIdentifier(viewer, "viewer role");
      requireSafeIdentifier(backup, "backup role");
    }

    List<String> runtimeRoles() {
      return List.of(app, bridge, viewer, backup);
    }

    String allRuntimeSql() {
      return String.join(", ", runtimeRoles());
    }

    String writersSql() {
      return app + ", " + bridge;
    }

    String readersSql() {
      return viewer + ", " + backup;
    }
  }
}

package dev.christopherbell.configuration.persistence.migration;

import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read-only source-digest entry point used to bind one production finalization request. */
public final class PostgresqlMigrationSourceSnapshotCli {
  private PostgresqlMigrationSourceSnapshotCli() {}

  public static void main(String[] arguments) {
    var exit = execute(arguments, System.getenv(), System.out, System.err);
    if (exit != 0) {
      System.exit(exit);
    }
  }

  static int execute(
      String[] arguments,
      Map<String, String> environment,
      PrintStream output,
      PrintStream error) {
    try {
      if (arguments.length != 1 || !"snapshot".equals(arguments[0])) {
        throw invalid();
      }
      output.println(snapshot(environment));
      return 0;
    } catch (RuntimeException | IOException failure) {
      error.println("PostgreSQL migration source snapshot command failed.");
      return 2;
    }
  }

  static String snapshot(Map<String, String> environment) throws IOException {
    var catalogBytes = loadCatalogResource();
    var catalog =
        new PostgresqlMigrationCatalogLoader()
            .load(new java.io.ByteArrayInputStream(catalogBytes));
    var catalogDigest = sha256(catalogBytes);
    var request =
        new MigrationRequest(
            PostgresqlMigrationCommand.STATUS,
            required(environment, "POSTGRESQL_MIGRATION_SOURCE_URI"),
            required(environment, "POSTGRESQL_MIGRATION_SOURCE_DATABASE"),
            required(environment, "POSTGRESQL_MIGRATION_TARGET_JDBC_URL"),
            required(environment, "POSTGRESQL_MIGRATION_TARGET_DATABASE"),
            required(environment, "POSTGRESQL_MIGRATION_TARGET_ROLE"),
            environment.getOrDefault("POSTGRESQL_MIGRATION_SCHEMA_PREFIX", ""),
            catalogDigest,
            required(environment, "POSTGRESQL_MIGRATION_RELEASE"),
            Integer.parseInt(required(environment, "POSTGRESQL_MIGRATION_BRIDGE_RELEASE")),
            UUID.fromString(required(environment, "POSTGRESQL_MIGRATION_LOCK_TOKEN")),
            null,
            Integer.parseInt(required(environment, "POSTGRESQL_MIGRATION_BATCH_SIZE")));
    catalog.requireCompatibleBridgeRelease(request.bridgeRelease());
    var dataSource =
        new DriverManagerDataSource(
            request.targetJdbcUrl(),
            required(environment, "POSTGRESQL_MIGRATION_TARGET_USERNAME"),
            required(environment, "POSTGRESQL_MIGRATION_TARGET_PASSWORD"));
    var context = new MigrationPreflight(new DirectMigrationIdentityProbe(dataSource)).validate(request);
    var registry = MigrationTransformerRegistry.from(catalog);
    try (var mongo = MongoClients.create(request.sourceUri())) {
      var source = new MongoMigrationSourceReader(mongo);
      source.requireOnlyCatalogKinds(context, catalog);
      var snapshots = new ArrayList<MigrationSourceSnapshot>();
      for (var kind :
          catalog.kinds().stream()
              .sorted(java.util.Comparator.comparingInt(PostgresqlMigrationCatalog.Kind::loadOrder))
              .toList()) {
        snapshots.add(
            KindMigrationEngine.readSourceSnapshot(
                source, context, kind, registry.require(kind.sourceKind())));
      }
      return "catalogDigest="
          + catalogDigest
          + " sourceDigest="
          + MigrationSourceSnapshot.runDigest(snapshots)
          + " kinds="
          + snapshots.size();
    }
  }

  private static byte[] loadCatalogResource() throws IOException {
    try (var input = PostgresqlMigrationSourceSnapshotCli.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      if (input == null) {
        throw invalid();
      }
      return input.readAllBytes();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  private static String required(Map<String, String> environment, String key) {
    var value = environment.get(key);
    if (value == null || value.isBlank()) {
      throw invalid();
    }
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "PostgreSQL migration source snapshot command configuration is invalid.");
  }
}

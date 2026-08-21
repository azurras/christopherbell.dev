package dev.christopherbell.configuration.persistence.migration;

import com.mongodb.client.MongoClients;
import java.io.IOException;
import java.io.PrintStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Standalone environment-driven migration entry point; never registered as a web bean or route. */
public final class PostgresqlMigrationCli {
  private PostgresqlMigrationCli() {}

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
      if (arguments.length != 1) {
        throw invalid();
      }
      var command = PostgresqlMigrationCommand.parse(arguments[0]);
      var catalogResource = loadCatalogResource();
      var catalog = new PostgresqlMigrationCatalogLoader().load(
          new java.io.ByteArrayInputStream(catalogResource));
      var request = request(command, environment, digest(catalogResource));
      catalog.requireCompatibleBridgeRelease(request.bridgeRelease());
      var dataSource = new DriverManagerDataSource(
          request.targetJdbcUrl(), required(environment, "POSTGRESQL_MIGRATION_TARGET_USERNAME"),
          required(environment, "POSTGRESQL_MIGRATION_TARGET_PASSWORD"));
      var target = new JdbcMigrationTargetStore(
          dataSource, new JdbcRelationalRowPublisher(), catalog);
      var registry = MigrationTransformerRegistry.from(catalog);
      try (var mongo = MongoClients.create(request.sourceUri())) {
        var runner = new PostgresqlMigrationRunner(
            new MigrationPreflight(new DirectMigrationIdentityProbe(dataSource)),
            catalog,
            new KindMigrationEngine(
                new MongoMigrationSourceReader(mongo), target, registry::require),
            new MigrationReconciler(target),
            target,
            expected -> FinalizeEvidenceLoader.loadProduction(),
            (context, evidence) -> ProductionFinalizationFreezeGuard.acquire(mongo));
        var result = runner.run(request);
        output.printf(
            "command=%s kinds=%d statusDigest=%s%n",
            result.command().name().toLowerCase(java.util.Locale.ROOT),
            result.kinds().size(),
            result.statusDigest());
      }
      return 0;
    } catch (RuntimeException | IOException failure) {
      error.println("PostgreSQL migration command failed.");
      return 2;
    }
  }

  private static MigrationRequest request(
      PostgresqlMigrationCommand command,
      Map<String, String> environment,
      String catalogDigest) {
    var lockToken = UUID.fromString(required(environment, "POSTGRESQL_MIGRATION_LOCK_TOKEN"));
    FrozenSourceEvidence evidence = null;
    if (command == PostgresqlMigrationCommand.FINALIZE) {
      evidence = FinalizeEvidenceLoader.loadProduction();
    }
    return new MigrationRequest(
        command,
        required(environment, "POSTGRESQL_MIGRATION_SOURCE_URI"),
        required(environment, "POSTGRESQL_MIGRATION_SOURCE_DATABASE"),
        required(environment, "POSTGRESQL_MIGRATION_TARGET_JDBC_URL"),
        required(environment, "POSTGRESQL_MIGRATION_TARGET_DATABASE"),
        required(environment, "POSTGRESQL_MIGRATION_TARGET_ROLE"),
        environment.getOrDefault("POSTGRESQL_MIGRATION_SCHEMA_PREFIX", ""),
        catalogDigest,
        required(environment, "POSTGRESQL_MIGRATION_RELEASE"),
        Integer.parseInt(required(environment, "POSTGRESQL_MIGRATION_BRIDGE_RELEASE")),
        lockToken,
        evidence,
        Integer.parseInt(required(environment, "POSTGRESQL_MIGRATION_BATCH_SIZE")));
  }

  private static byte[] loadCatalogResource() throws IOException {
    try (var input = PostgresqlMigrationCli.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      if (input == null) {
        throw invalid();
      }
      return input.readAllBytes();
    }
  }

  private static String digest(byte[] bytes) {
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
    return new IllegalArgumentException("PostgreSQL migration command configuration is invalid.");
  }
}

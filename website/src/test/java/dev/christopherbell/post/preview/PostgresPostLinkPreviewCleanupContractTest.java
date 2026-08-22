package dev.christopherbell.post.preview;

import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresPostLinkPreviewCleanupContractTest implements PostLinkPreviewCleanupParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostLinkPreviewCacheRepository repository;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    repository = new PostgresPostLinkPreviewCacheRepository(database.jdbc(), database.schemas());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override
  public PostLinkPreviewCacheRepository parityRepository() {
    return repository;
  }
}

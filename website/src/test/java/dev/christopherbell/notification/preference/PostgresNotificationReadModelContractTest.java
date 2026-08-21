package dev.christopherbell.notification.preference;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.PostgresNotificationRepository;
import dev.christopherbell.notification.inbox.NotificationQueryPort;
import dev.christopherbell.notification.inbox.PostgresNotificationQueryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresNotificationReadModelContractTest implements NotificationReadModelParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static PostgresAccountRepository accounts;
  private static NotificationRepository notifications;
  private static NotificationQueryPort queries;
  private static NotificationPreferenceRepository preferences;
  private static StableCursorCodec cursors;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    accounts = new PostgresAccountRepository(database.dsl());
    notifications = new PostgresNotificationRepository(database.dsl());
    cursors = new StableCursorCodec();
    queries = new PostgresNotificationQueryRepository(database.dsl(), cursors);
    preferences = new PostgresNotificationPreferenceRepository(database.dsl());
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override public NotificationRepository notifications() { return notifications; }
  @Override public NotificationQueryPort queries() { return queries; }
  @Override public NotificationPreferenceRepository preferences() { return preferences; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}

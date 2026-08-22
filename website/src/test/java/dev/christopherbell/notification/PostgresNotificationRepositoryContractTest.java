package dev.christopherbell.notification;

import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresNotificationRepositoryContractTest implements NotificationRepositoryParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static NotificationRepository notifications;
  @BeforeAll static void migrate() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    var accounts = new PostgresAccountRepository(
        database.managedJdbc(), database.schemas(), database.transactions());
    accounts.save(account("notification-parity-owner"));
    accounts.save(account("notification-parity-actor"));
    notifications = new PostgresNotificationRepository(database.jdbc(), database.schemas());
  }
  @AfterAll static void cleanup() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }
  @Override public NotificationRepository parityNotifications() { return notifications; }
  private static Account account(String id) {
    return Account.builder().id(id).createdOn(CREATED.minusSeconds(1))
        .email(id + "@example.test").passwordHash("hash").role(Role.USER)
        .status(AccountStatus.ACTIVE).username(id).build();
  }
}

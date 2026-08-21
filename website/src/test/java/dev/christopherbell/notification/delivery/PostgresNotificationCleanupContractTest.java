package dev.christopherbell.notification.delivery;

import dev.christopherbell.configuration.postgresql.Task3PostgresqlTestSupport;
import dev.christopherbell.account.PostgresAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "POSTGRESQL_INTEGRATION_TESTS", matches = "enabled")
class PostgresNotificationCleanupContractTest implements NotificationCleanupParityContract {
  private static Task3PostgresqlTestSupport schemas;
  private static Task3PostgresqlTestSupport.Database database;
  private static NotificationFanoutPort fanout;

  @BeforeAll
  static void migrateDatabase() throws Exception {
    schemas = Task3PostgresqlTestSupport.migrate();
    database = schemas.openDatabase();
    var accounts = new PostgresAccountRepository(database.dsl());
    accounts.save(account("cleanup-recipient-a", "cleanup-recipient-a"));
    accounts.save(account("cleanup-recipient-b", "cleanup-recipient-b"));
    accounts.save(account("cleanup-actor", "cleanup-actor"));
    fanout = new PostgresNotificationFanoutGuard(database.dsl(),
        new NotificationDeliveryProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 10));
  }

  @AfterAll
  static void cleanupDatabase() throws Exception {
    if (database != null) database.close();
    if (schemas != null) schemas.close();
  }

  @Override
  public NotificationFanoutPort parityFanout() {
    return fanout;
  }

  private static Account account(String id, String username) {
    return Account.builder().id(id).createdOn(CUTOFF.minusSeconds(100))
        .email(username + "@example.test").passwordHash("hash")
        .role(Role.USER).status(AccountStatus.ACTIVE).username(username).build();
  }
}

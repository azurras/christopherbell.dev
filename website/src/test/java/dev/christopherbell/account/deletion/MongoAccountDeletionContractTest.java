package dev.christopherbell.account.deletion;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoAccountDeletionContractTest implements AccountDeletionParityContract {
  private static MongoClient client;
  private static MongoAccountRepository accounts;
  private static AccountDeletionJobRepository jobs;
  private static AccountDeletionOperations operations;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(client, "test"));
    accounts = new MongoAccountRepository(factory);
    jobs = new MongoAccountDeletionJobRepository(factory);
    operations = new MongoAccountDeletionOperations(factory, accountId -> {});
  }

  @AfterAll
  static void disconnect() {
    if (client != null) {
      client.close();
    }
  }

  @Override public AccountDeletionJobRepository jobs() { return jobs; }
  @Override public AccountDeletionOperations operations() { return operations; }
  @Override public void createAccount(Account account) { accounts.save(account); }
}

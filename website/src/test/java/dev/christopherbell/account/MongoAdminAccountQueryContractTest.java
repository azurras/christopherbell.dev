package dev.christopherbell.account;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoAdminAccountQueryContractTest implements AdminAccountQueryParityContract {
  private static MongoClient client;
  private static AccountRepository accounts;
  private static AdminAccountQueryPort queries;

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
    queries = new AdminAccountQueryService(factory, new AccountMapperImpl());
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public AccountRepository accounts() { return accounts; }
  @Override public AdminAccountQueryPort adminQueries() { return queries; }
}

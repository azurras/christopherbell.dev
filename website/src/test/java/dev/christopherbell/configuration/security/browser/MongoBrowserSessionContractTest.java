package dev.christopherbell.configuration.security.browser;

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
class MongoBrowserSessionContractTest implements BrowserSessionParityContract {
  private static MongoClient client;
  private static MongoTemplate mongo;
  private static MongoAccountRepository accounts;
  private static BrowserSessionRepository sessions;
  private static BrowserSessionAuthenticationStore authentication;
  private static BrowserSessionActivityStore activity;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    mongo = new MongoTemplate(client, "test");
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
    accounts = new MongoAccountRepository(factory);
    sessions = new MongoBrowserSessionRepository(factory);
    authentication = new MongoBrowserSessionAuthenticationStore(factory);
    activity = new MongoBrowserSessionActivityStore(factory);
  }

  @AfterAll
  static void disconnect() {
    if (client != null) {
      client.close();
    }
  }

  @Override public BrowserSessionRepository sessions() { return sessions; }
  @Override public BrowserSessionAuthenticationStore authentication() { return authentication; }
  @Override public BrowserSessionActivityStore activity() { return activity; }
  @Override public void createAccount(Account account) { accounts.save(account); }
  @Override
  public void resetFixture() {
    mongo.getCollection("sessions").deleteMany(
        com.mongodb.client.model.Filters.eq("_id.legacyId", SESSION_ID));
    mongo.getCollection("accounts").deleteMany(
        com.mongodb.client.model.Filters.eq("_id.legacyId", ACCOUNT_ID));
  }
}

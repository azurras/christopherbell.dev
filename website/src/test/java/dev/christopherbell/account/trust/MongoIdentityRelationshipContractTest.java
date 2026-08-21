package dev.christopherbell.account.trust;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.account.follow.AccountFollowStore;
import dev.christopherbell.account.follow.MongoAccountFollowStore;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoIdentityRelationshipContractTest implements IdentityRelationshipParityContract {
  private static MongoClient client;
  private static MongoAccountRepository accounts;
  private static AccountFollowStore follows;
  private static AccountTrustRepository trust;

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
    follows = new MongoAccountFollowStore(factory);
    trust = new MongoAccountTrustRepository(factory);
  }

  @AfterAll
  static void disconnect() {
    if (client != null) client.close();
  }

  @Override public AccountFollowStore followStore() { return follows; }
  @Override public AccountTrustRepository trustRepository() { return trust; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}

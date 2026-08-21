package dev.christopherbell.post.hide;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.post.model.Post;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoHiddenPostThreadContractTest implements HiddenPostThreadParityContract {
  private static MongoClient client;
  private static HiddenPostThreadRepository hiddenThreads;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(client, "test"));
    hiddenThreads = new MongoHiddenPostThreadRepository(factory);
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public HiddenPostThreadRepository hiddenThreads() { return hiddenThreads; }
  @Override public void ensureAccountAndPost(Account account, Post post) {}
}

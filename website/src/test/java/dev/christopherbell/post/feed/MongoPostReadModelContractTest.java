package dev.christopherbell.post.feed;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.MongoPostRepositoryTestFactory;
import dev.christopherbell.post.PostRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoPostReadModelContractTest implements PostReadModelParityContract {
  private static MongoClient client;
  private static MongoAccountRepository accounts;
  private static PostRepository posts;
  private static PostFeedQueryPort feed;
  private static PostEngagementQueryPort engagement;
  private static StableCursorCodec cursors;

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
    posts = MongoPostRepositoryTestFactory.create(factory);
    cursors = new StableCursorCodec();
    feed = new PostFeedQueryRepository(factory, cursors);
    engagement = new PostEngagementQueryRepository(factory);
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public PostRepository posts() { return posts; }
  @Override public PostFeedQueryPort feed() { return feed; }
  @Override public PostEngagementQueryPort engagement() { return engagement; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}

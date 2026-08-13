package dev.christopherbell.post;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.post.expiration.MongoPostExpirationStore;
import dev.christopherbell.post.expiration.PostExpirationStore;
import dev.christopherbell.post.like.MongoPostLikeStore;
import dev.christopherbell.post.like.PostLikeStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoPostInteractionContractTest implements PostInteractionParityContract {
  private static MongoClient client;
  private static AccountRepository accounts;
  private static PostRepository posts;
  private static PostLikeStore likes;
  private static PostExpirationStore expiration;

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
    posts = new MongoPostRepository(factory);
    likes = new MongoPostLikeStore(factory);
    expiration = new MongoPostExpirationStore(factory);
  }

  @AfterAll
  static void disconnect() {
    if (client != null) client.close();
  }

  @Override public AccountRepository accounts() { return accounts; }
  @Override public PostRepository posts() { return posts; }
  @Override public PostLikeStore likes() { return likes; }
  @Override public PostExpirationStore expiration() { return expiration; }
}

package dev.christopherbell.post.discovery;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.post.MongoPostRepositoryTestFactory;
import dev.christopherbell.post.PostRepository;
import dev.christopherbell.post.like.MongoPostLikeStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoPostDiscoveryContractTest implements PostDiscoveryParityContract {
  private static MongoClient client;
  private static MongoAccountRepository accounts;
  private static PostRepository posts;
  private static VoidDiscoveryQueryPort discovery;
  private static VoidPeopleDiscoveryQueryPort people;
  private static StableCursorCodec cursors;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var mongo = new MongoTemplate(client, "test");
    mongo.getCollection("content").deleteMany(
        com.mongodb.client.model.Filters.eq("_kind", "post"));
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
    accounts = new MongoAccountRepository(factory);
    posts = MongoPostRepositoryTestFactory.create(factory);
    cursors = new StableCursorCodec();
    discovery = new VoidDiscoveryQueryRepository(factory, cursors);
    people = new VoidPeopleDiscoveryQueryRepository(factory, new MongoPostLikeStore(factory));
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public PostRepository posts() { return posts; }
  @Override public VoidDiscoveryQueryPort discovery() { return discovery; }
  @Override public VoidPeopleDiscoveryQueryPort people() { return people; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}

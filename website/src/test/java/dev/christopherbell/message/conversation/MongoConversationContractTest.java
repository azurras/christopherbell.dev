package dev.christopherbell.message.conversation;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.message.MessageRepository;
import dev.christopherbell.message.MongoMessageRepositoryTestFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoConversationContractTest implements ConversationParityContract {
  private static MongoClient client;
  private static MongoAccountRepository accounts;
  private static MessageRepository messages;
  private static ConversationQueryPort queries;
  private static ConversationArchivePort archives;
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
    messages = MongoMessageRepositoryTestFactory.create(factory);
    cursors = new StableCursorCodec();
    queries = new ConversationQueryRepository(factory, cursors);
    archives = new ConversationArchiveService(factory);
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public MessageRepository messages() { return messages; }
  @Override public ConversationQueryPort queries() { return queries; }
  @Override public ConversationArchivePort archives() { return archives; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}

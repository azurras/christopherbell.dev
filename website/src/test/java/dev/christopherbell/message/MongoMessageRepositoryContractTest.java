package dev.christopherbell.message;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoMessageRepositoryContractTest implements MessageRepositoryParityContract {
  private static MongoClient client;
  private static MessageRepository messages;
  @BeforeAll static void connect() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) throw new IllegalStateException("Database test required.");
    client = MongoClients.create(connection);
    messages = new MongoMessageRepository(DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(client, "test")));
  }
  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public MessageRepository parityMessages() { return messages; }
}

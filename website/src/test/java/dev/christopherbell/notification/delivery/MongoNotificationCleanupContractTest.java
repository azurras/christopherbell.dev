package dev.christopherbell.notification.delivery;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoNotificationCleanupContractTest implements NotificationCleanupParityContract {
  private static MongoClient client;
  private static NotificationFanoutPort fanout;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(client, "test"));
    fanout = new NotificationFanoutGuard(factory,
        new NotificationDeliveryProperties(Duration.ofMinutes(5), Duration.ofMinutes(1), 10));
  }

  @AfterAll
  static void disconnect() {
    if (client != null) client.close();
  }

  @Override
  public NotificationFanoutPort parityFanout() {
    return fanout;
  }
}

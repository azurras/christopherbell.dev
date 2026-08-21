package dev.christopherbell.notification;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoNotificationRepositoryContractTest implements NotificationRepositoryParityContract {
  private static MongoClient client;
  private static NotificationRepository notifications;
  @BeforeAll static void connect() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) throw new IllegalStateException("Database test required.");
    client = MongoClients.create(connection);
    notifications = new MongoNotificationRepository(
        DomainMongoOperationsTestFactory.createForDisposableMongo(new MongoTemplate(client, "test")));
  }
  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public NotificationRepository parityNotifications() { return notifications; }
}

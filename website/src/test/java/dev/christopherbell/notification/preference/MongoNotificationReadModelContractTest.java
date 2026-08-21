package dev.christopherbell.notification.preference;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.account.MongoAccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.libs.pagination.StableCursorCodec;
import dev.christopherbell.notification.MongoNotificationRepositoryTestFactory;
import dev.christopherbell.notification.NotificationRepository;
import dev.christopherbell.notification.inbox.NotificationQueryPort;
import dev.christopherbell.notification.inbox.NotificationQueryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoNotificationReadModelContractTest implements NotificationReadModelParityContract {
  private static MongoClient client;
  private static MongoAccountRepository accounts;
  private static NotificationRepository notifications;
  private static NotificationQueryPort queries;
  private static NotificationPreferenceRepository preferences;
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
    notifications = MongoNotificationRepositoryTestFactory.create(factory);
    cursors = new StableCursorCodec();
    queries = new NotificationQueryRepository(factory, cursors);
    preferences = new MongoNotificationPreferenceRepository(factory);
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public NotificationRepository notifications() { return notifications; }
  @Override public NotificationQueryPort queries() { return queries; }
  @Override public NotificationPreferenceRepository preferences() { return preferences; }
  @Override public StableCursorCodec cursors() { return cursors; }
  @Override public void ensureAccount(Account account) {
    if (accounts.findById(account.getId()).isEmpty()) accounts.save(account);
  }
}

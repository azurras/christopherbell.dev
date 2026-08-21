package dev.christopherbell.account;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoAccountRepositoryContractTest implements AccountRepositoryParityContract {
  private static MongoClient client;
  private static AccountRepository accounts;

  @BeforeAll
  static void connectToDisposableMongo() {
    var uri = System.getenv("SPRING_MONGODB_URI");
    var connection = new ConnectionString(uri);
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var mongo = new MongoTemplate(client, "test");
    mongo.getCollection("accounts").deleteMany(
        com.mongodb.client.model.Filters.and(
            com.mongodb.client.model.Filters.eq("_kind", "account"),
            com.mongodb.client.model.Filters.in(
                "_id.legacyId", FIXTURE_ID, FIXTURE_ID + "-duplicate")));
    mongo.getCollection("accounts").createIndex(
        com.mongodb.client.model.Indexes.ascending("payload.email"),
        new com.mongodb.client.model.IndexOptions().unique(true)
            .name("account_parity_email_unique_v2")
            .partialFilterExpression(com.mongodb.client.model.Filters.and(
                com.mongodb.client.model.Filters.eq("_kind", "account"),
                com.mongodb.client.model.Filters.eq("payload.firstName", "Parity"))));
    accounts = new MongoAccountRepository(
        DomainMongoOperationsTestFactory.createForDisposableMongo(mongo));
  }

  @AfterAll
  static void disconnect() {
    if (client != null) client.close();
  }

  @Override
  public AccountRepository parityRepository() {
    return accounts;
  }
}

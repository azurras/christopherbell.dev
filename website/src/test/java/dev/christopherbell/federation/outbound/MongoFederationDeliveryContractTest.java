package dev.christopherbell.federation.outbound;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import dev.christopherbell.post.model.Post;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "MONGODB_INTEGRATION_TESTS", matches = "enabled")
class MongoFederationDeliveryContractTest implements FederationDeliveryParityContract {
  private static MongoClient client;
  private static FederationDeliveryStore deliveries;
  private static Post post;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("SPRING_MONGODB_URI"));
    if (!"test".equals(connection.getDatabase())) {
      throw new IllegalStateException("MongoDB contract tests require database test.");
    }
    client = MongoClients.create(connection);
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(
        new MongoTemplate(client, "test"));
    deliveries = new FederationDeliveryJobRepository(factory);
    post = post();
  }

  @AfterAll static void disconnect() { if (client != null) client.close(); }
  @Override public FederationDeliveryStore deliveries() { return deliveries; }
  @Override public Post deliveryPost() { return post; }

  private static Post post() {
    var now = Instant.now().minus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MILLIS);
    return Post.builder().id("federation-parity-post-" + RUN).accountId("federation-owner")
        .text("federation").rootId("federation-parity-post-" + RUN).level(0).createdOn(now)
        .expiresOn(now.plus(Duration.ofDays(1))).federationOutboundEligible(true)
        .likesCount(0).threadReplyLikesCount(0).threadReplyCount(0).build();
  }
}

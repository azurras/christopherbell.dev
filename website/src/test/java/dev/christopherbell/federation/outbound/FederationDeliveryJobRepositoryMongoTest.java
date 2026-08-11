package dev.christopherbell.federation.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Real-Mongo ordering contract for the guarded Federation delivery claim. */
@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_TEST_URI", matches = ".+")
class FederationDeliveryJobRepositoryMongoTest {
  private static final String TEST_URI = System.getenv("DOMAIN_COLLECTION_TEST_URI");
  private static final Instant NOW = Instant.parse("2026-08-11T18:00:00Z");
  private static MongoClient client;

  private MongoTemplate mongo;
  private FederationDeliveryJobRepository repository;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(TEST_URI);
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("Federation claim test requires one disposable MongoDB.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException(
          "Federation claim test requires a non-production loopback MongoDB port.");
    }
    client = MongoClients.create(connection);
  }

  @BeforeEach
  void createDatabase() {
    var database = "federation_claim_contract_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    mongo = new MongoTemplate(client, database);
    var factory = DomainMongoOperationsTestFactory.createForDisposableMongo(mongo);
    repository = new FederationDeliveryJobRepository(factory);
    var jobs = factory.forType(FederationDeliveryJob.class);
    jobs.insert(job("inserted-first", NOW.minusSeconds(300), NOW.minusSeconds(7_200)));
    jobs.insert(job("earliest-due-later-created", NOW.minusSeconds(600), NOW.minusSeconds(1_800)));
    jobs.insert(job("expected", NOW.minusSeconds(600), NOW.minusSeconds(10_800)));
  }

  @AfterEach
  void dropDatabase() {
    mongo.getDb().drop();
  }

  @AfterAll
  static void closeClient() {
    client.close();
  }

  @Test
  void claimDueSelectsEarliestAttemptThenEarliestCreation() {
    var claimed = repository.claimDue("worker-a", NOW, NOW.plusSeconds(120)).orElseThrow();

    assertThat(claimed.id()).isEqualTo("expected");
    assertThat(claimed.state()).isEqualTo(FederationDeliveryState.CLAIMED);
    assertThat(claimed.claimOwner()).isEqualTo("worker-a");
    assertThat(claimed.attempts()).isEqualTo(1);
  }

  private static FederationDeliveryJob job(
      String id, Instant nextAttemptOn, Instant createdOn) {
    return new FederationDeliveryJob(
        id,
        "post-" + id,
        "account-1",
        "peer-a",
        "https://peer.example/inbox",
        FederationDeliveryState.PENDING,
        0,
        nextAttemptOn,
        null,
        null,
        null,
        null,
        createdOn,
        createdOn);
  }
}

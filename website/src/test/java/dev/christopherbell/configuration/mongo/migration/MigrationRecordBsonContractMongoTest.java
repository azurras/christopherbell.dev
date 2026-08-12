package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.ConnectionString;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

@EnabledIfEnvironmentVariable(named = "DOMAIN_COLLECTION_MIGRATION_TEST_URI", matches = ".+")
class MigrationRecordBsonContractMongoTest {
  private static final String DATABASE =
      "cbell_candidate_b0b0b0b0b0b0_b0b0b0b0b0b0b0b0b0b0b0b0";
  private static MongoClient client;

  @BeforeAll
  static void connectToDisposableMongo() {
    var connection = new ConnectionString(System.getenv("DOMAIN_COLLECTION_MIGRATION_TEST_URI"));
    if (connection.getHosts().size() != 1) {
      throw new IllegalStateException("V014 BSON contract requires one disposable MongoDB host.");
    }
    var address = new ServerAddress(connection.getHosts().getFirst());
    if (!"127.0.0.1".equals(address.getHost()) || address.getPort() == 27_017) {
      throw new IllegalStateException("V014 BSON contract requires disposable loopback MongoDB.");
    }
    client = MongoClients.create(connection);
  }

  @AfterAll
  static void closeClient() {
    client.getDatabase(DATABASE).drop();
    client.close();
  }

  @Test
  void mongoTemplatePersistsTheExactV014LegacyRecordShape() {
    var mongo = new MongoTemplate(client, DATABASE);
    var record = record();

    mongo.insert(record, "application_migrations");
    var stored = mongo.getCollection("application_migrations")
        .find(new Document("_id", record.getId())).first();

    assertThat(stored).isNotNull();
    assertThat(List.copyOf(stored.keySet())).containsExactly(
        "_id", "checksum", "description", "status", "ownerToken", "startedAt",
        "completedAt", "_class");
    assertThat(stored.get("_id")).isInstanceOf(String.class);
    assertThat(stored.get("status")).isEqualTo("APPLIED");
    assertThat(stored.get("startedAt")).isEqualTo(Date.from(record.getStartedAt()));
    assertThat(stored.get("completedAt")).isEqualTo(Date.from(record.getCompletedAt()));
    assertThat(stored.get("_class")).isEqualTo(MigrationRecord.class.getName());
  }

  private static MigrationRecord record() {
    var record = new MigrationRecord();
    record.setId("014-consolidate-music-runtime-state");
    record.setChecksum("11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb");
    record.setDescription("Consolidate Music queue and radio runtime state");
    record.setStatus(MigrationStatus.APPLIED);
    record.setOwnerToken("v014-owner");
    record.setStartedAt(Instant.parse("2026-08-10T00:00:00Z"));
    record.setCompletedAt(Instant.parse("2026-08-10T00:01:00Z"));
    return record;
  }
}

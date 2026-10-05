package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.mongodb.core.MongoTemplate;

class DomainCollectionCutoverLedgerTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final MockEnvironment environment = new MockEnvironment();

  @Test
  void acceptsOnlyCompletedTargetActiveLedgerForExactDigest() {
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST));

    assertThatCode(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetActive(DomainCollectionManifest.DIGEST)).doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingWrongDigestIncompleteAndNonTargetLedgers() {
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(null)
        .thenReturn(envelope("TARGET_ACTIVE", true, "0".repeat(64)))
        .thenReturn(envelope("TARGET_ACTIVE", false, DomainCollectionManifest.DIGEST))
        .thenReturn(envelope("PUBLISHING", true, DomainCollectionManifest.DIGEST));

    for (int attempt = 0; attempt < 4; attempt++) {
      assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
          .requireTargetActive(DomainCollectionManifest.DIGEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Domain collection schema is not active.")
          .hasNoCause();
    }
  }

  @Test
  void rejectsMalformedEnvelopeWithoutLeakingStoredValues() {
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(new Document("_id", new Document("kind", "wrong")
            .append("legacyId", "sensitive"))
            .append("_kind", "domain_collection_cutover")
            .append("schemaVersion", 1)
            .append("payload", new Document("state", "TARGET_ACTIVE")
                .append("manifestDigest", DomainCollectionManifest.DIGEST)
                .append("completed", true)));

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetActive(DomainCollectionManifest.DIGEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Domain collection schema is not active.")
        .hasNoCause()
        .satisfies(failure -> assertThatCode(() -> {
          if (failure.toString().contains("sensitive") || failure.toString().contains("wrong")) {
            throw new AssertionError("stored values leaked");
          }
        }).doesNotThrowAnyException());
  }

  @Test
  void rejectsExtraReorderedAndMistypedPayloadFields() {
    var extra = envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST);
    extra.get("payload", Document.class).append("publicationOperations", java.util.List.of());
    var reordered = envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST);
    var payload = reordered.get("payload", Document.class);
    var state = payload.remove("state");
    payload.append("state", state);
    var mistyped = envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST);
    mistyped.get("payload", Document.class).put("dropIndex", 0L);
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(extra, reordered, mistyped);

    for (int attempt = 0; attempt < 3; attempt++) {
      assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
          .requireTargetSchemaReady())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Domain collection schema is not active.");
    }
  }

  @Test
  void permitsAnEmptyDatabaseOnlyForAnExplicitIsolatedTestProfile() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.getCollectionNames()).thenReturn(Set.of("application_migrations", "application_runtime"));
    when(mongo.findAll(Document.class, "application_migrations"))
        .thenReturn(migrationRecords("RUNNING"));
    when(mongo.findAll(Document.class, "application_runtime"))
        .thenReturn(List.of(migrationLeaseRecord(true)));
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations"))).thenReturn(null);

    assertThatCode(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .doesNotThrowAnyException();
  }

  @Test
  void requiresTheRunnerLeaseWhileV015IsRunning() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.getCollectionNames()).thenReturn(Set.of("application_migrations"));
    when(mongo.findAll(Document.class, "application_migrations"))
        .thenReturn(migrationRecords("RUNNING"));
    when(mongo.findAll(Document.class, "application_runtime")).thenReturn(List.of());
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations"))).thenReturn(null);

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test Mongo V015 migration requires its migration lease.");
  }

  @Test
  void permitsFreshTestPreflightBeforeTheMigrationRunnerStarts() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.getCollectionNames()).thenReturn(Set.of());
    when(mongo.findAll(Document.class, "application_migrations")).thenReturn(List.of());
    when(mongo.findAll(Document.class, "application_runtime")).thenReturn(List.of());
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations"))).thenReturn(null);

    assertThatCode(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady()).doesNotThrowAnyException();
  }

  @Test
  void acceptsAppliedV015AsTheDurableEmptyTestBootstrapMarker() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.getCollectionNames()).thenReturn(Set.of("application_migrations", "application_runtime"));
    when(mongo.findAll(Document.class, "application_migrations"))
        .thenReturn(migrationRecords("APPLIED"));
    when(mongo.findAll(Document.class, "application_runtime"))
        .thenReturn(List.of(migrationLeaseRecord(false)));
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations"))).thenReturn(null);

    assertThatCode(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady()).doesNotThrowAnyException();
  }

  @Test
  void acceptsARealCutoverLedgerOnTheIsolatedTestDatabase() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.getCollectionNames()).thenReturn(Set.of("application_migrations", "application_runtime"));
    var migrationStateWithActiveLedger = new ArrayList<>(migrationRecords("APPLIED"));
    migrationStateWithActiveLedger.add(envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST));
    when(mongo.findAll(Document.class, "application_migrations"))
        .thenReturn(migrationStateWithActiveLedger);
    when(mongo.findAll(Document.class, "application_runtime"))
        .thenReturn(List.of(migrationLeaseRecord(false)));
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST));

    assertThatCode(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .doesNotThrowAnyException();
  }

  @Test
  @SuppressWarnings("unchecked")
  void rejectsDomainDocumentsEvenWhenTheTestDatabaseHasARealCutoverLedger() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST));
    when(mongo.getCollectionNames()).thenReturn(Set.of("accounts", "application_migrations"));
    var accounts = mock(MongoCollection.class);
    when(mongo.getCollection("accounts")).thenReturn(accounts);
    when(accounts.countDocuments()).thenReturn(1L);

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test Mongo database contains application data.");
  }

  @Test
  void refusesTheProductionMongoPortEvenForTheTestDatabase() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:27017/test");

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test Mongo connection must use a non-production loopback port.");
    verify(mongo, never()).findAll(Document.class, "application_migrations");
  }

  @Test
  void refusesAProductionDatabaseEvenOnAnIsolatedPort() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/christopherbell");

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test Mongo connection must target the isolated test database.");
  }

  @Test
  @SuppressWarnings("unchecked")
  void refusesDomainDocumentsInAnOtherwiseKnownTestCollection() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.getCollectionNames()).thenReturn(Set.of("accounts", "application_migrations"));
    var accounts = mock(MongoCollection.class);
    when(mongo.getCollection("accounts")).thenReturn(accounts);
    when(accounts.countDocuments()).thenReturn(1L);

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test Mongo database contains application data.");
  }

  @Test
  void refusesUnknownCollectionsAndMalformedCutoverRecords() {
    environment.setActiveProfiles("test");
    stubTestDatabaseConnection("mongodb://127.0.0.1:63152/test");
    when(mongo.getCollectionNames()).thenReturn(Set.of("unknown_collection"));
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations"))).thenReturn(null);

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test Mongo database contains an unapproved collection.");

    when(mongo.getCollectionNames()).thenReturn(Set.of("application_migrations"));
    when(mongo.findAll(Document.class, "application_migrations"))
        .thenReturn(List.of(new Document("_id", new Document("kind", "domain_collection_cutover")
            .append("legacyId", DomainCollectionCutoverLedger.LEGACY_ID))
            .append("_kind", "domain_collection_cutover")
            .append("schemaVersion", 1)
            .append("payload", new Document("state", "PUBLISHING"))));

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Test Mongo migration state is not a pristine bootstrap.");
  }

  @Test
  void requiresTheGenuineLedgerOutsideTheExactTestProfile() {
    environment.setActiveProfiles("test", "prod");
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations"))).thenReturn(null);

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo, environment)
        .requireTargetSchemaReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Domain collection schema is not active.");
  }

  @Test
  void matchesTheSharedJavaScriptLedgerFieldContract() throws Exception {
    var resource = getClass().getResourceAsStream("/domain-collection-ledger-contract.txt");
    assertThat(resource).isNotNull();
    var contract = Arrays.stream(new String(resource.readAllBytes(), StandardCharsets.UTF_8)
        .strip().split("\\R"))
        .map(line -> line.split("\\|", 2))
        .collect(Collectors.toMap(parts -> parts[0], parts -> List.of(parts[1].split(","))));
    var stored = envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST);

    assertThat(List.copyOf(stored.keySet())).isEqualTo(contract.get("envelope"));
    assertThat(List.copyOf(stored.get("_id", Document.class).keySet()))
        .isEqualTo(contract.get("id"));
    assertThat(List.copyOf(stored.get("payload", Document.class).keySet()))
        .isEqualTo(contract.get("payload"));
    assertThat(List.copyOf(stored.get("payload", Document.class)
        .getList("expectedKindMetrics", Document.class).getFirst().keySet()))
        .isEqualTo(contract.get("metric"));
  }

  private static Document envelope(String state, boolean completed, String digest) {
    var presentSources = new ArrayList<String>();
    var uniqueSources = new LinkedHashSet<String>();
    DomainCollectionManifest.ALL_KINDS.stream()
        .flatMap(kind -> kind.legacySource().stream())
        .forEach(uniqueSources::add);
    presentSources.addAll(uniqueSources.stream().sorted().toList());
    var metrics = DomainCollectionManifest.ALL_KINDS.stream()
        .map(kind -> new Document("kind", kind.kind())
            .append("count", 0)
            .append("checksum", "0".repeat(64)))
        .toList();
    return new Document("_id", new Document("kind", "domain_collection_cutover")
        .append("legacyId", DomainCollectionCutoverLedger.LEGACY_ID))
        .append("_kind", "domain_collection_cutover")
        .append("schemaVersion", 1)
        .append("payload", new Document("state", state)
            .append("manifestDigest", digest)
            .append("ownerToken", "0".repeat(32))
            .append("release", "1".repeat(40))
            .append("backupIdentity", "2".repeat(64))
            .append("evidenceDigest", "3".repeat(64))
            .append("revision", 7)
            .append("stageIndex", 66)
            .append("publishIndex", 19)
            .append("dropIndex", 0)
            .append("completed", completed)
            .append("legacyDropped", false)
            .append("intent", null)
            .append("presentSources", presentSources)
            .append("expectedKindMetrics", metrics));
  }

  private void stubTestDatabaseConnection(String connectionUri) {
    var database = mock(MongoDatabase.class);
    when(database.getName()).thenReturn("test");
    when(mongo.getDb()).thenReturn(database);
    environment.setProperty("spring.mongodb.uri", connectionUri);
  }

  private static List<Document> migrationRecords(String v015Status) {
    var migrationIds = List.of(
        "001-ensure-migration-infrastructure",
        "002-ensure-restaurant-import-preview-indexes",
        "003-ensure-vin-preview-collector-indexes",
        "004-ensure-void-discovery-indexes",
        "005-ensure-void-people-discovery-indexes",
        "006-ensure-federation-actor-index",
        "007-ensure-federation-outbound-indexes",
        "008-remove-account-approval-fields",
        "009-move-social-relationships-to-edges",
        "010-backfill-post-expiration-metrics",
        "011-harden-whats-for-lunch-data",
        "012-retain-shared-folder-work",
        "013-convert-restaurant-ratings-to-votes",
        "014-consolidate-music-runtime-state",
        "015-require-domain-collection-schema");
    return migrationIds.stream().map(migrationId -> {
      var status = migrationId.startsWith("015-") ? v015Status : "APPLIED";
      var checksum = migrationId.startsWith("015-")
          ? DomainCollectionManifest.DIGEST
          : "0".repeat(64);
      var migrationPayload = new Document("checksum", checksum)
          .append("description", migrationId)
          .append("status", status)
          .append("ownerToken", "migration-owner")
          .append("startedAt", Date.from(Instant.EPOCH));
      if ("APPLIED".equals(status)) {
        migrationPayload.append("completedAt", Date.from(Instant.EPOCH));
      }
      return new Document("_id", new Document("kind", "migration_record")
          .append("legacyId", migrationId))
          .append("_kind", "migration_record")
          .append("schemaVersion", 1)
          .append("payload", migrationPayload);
    }).toList();
  }

  private static Document migrationLeaseRecord(boolean isActive) {
    var leasePayload = new Document("fenceToken", 1L)
        .append("acquiredAt", Date.from(Instant.EPOCH))
        .append("expiresAt", Date.from(isActive
            ? Instant.parse("2099-01-01T00:00:00Z")
            : Instant.EPOCH));
    if (isActive) {
      leasePayload.append("ownerToken", "migration-owner");
    }
    return new Document("_id", new Document("kind", "application_lease")
        .append("legacyId", "application-migrations"))
        .append("_kind", "application_lease")
        .append("schemaVersion", 1)
        .append("payload", leasePayload);
  }
}

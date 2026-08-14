package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationTransformerAllKindsTest {
  @Test
  void everyManifestKindTransformsDeterministicallyWithEveryDeclaredConversion() throws IOException {
    var catalog = loadCatalog();
    var registry = MigrationTransformerRegistry.from(catalog);

    assertThat(catalog.kinds()).hasSize(52);
    for (var kind : catalog.kinds()) {
      var source = new MigrationSourceDocument(
          kind.sourceKind(), kind.sourceSchemaVersion(), "id-🛰-" + kind.sourceKind(),
          representativePayload(kind));
      TransformedMigrationDocument first;
      TransformedMigrationDocument second;
      try {
        first = registry.require(kind.sourceKind()).transform(source);
        second = registry.require(kind.sourceKind()).transform(source);
      } catch (RuntimeException failure) {
        throw new AssertionError("Production fixture failed for " + kind.sourceKind(), failure);
      }

      assertThat(first.sourceHash()).isEqualTo(second.sourceHash());
      assertThat(first.rows()).hasSameSizeAs(second.rows());
      for (var index = 0; index < first.rows().size(); index++) {
        assertThat(CanonicalMigrationHasher.sha256(first.rows().get(index).values()))
            .isEqualTo(CanonicalMigrationHasher.sha256(second.rows().get(index).values()));
      }
      assertThat(first.sourceHash()).matches("[0-9a-f]{64}");
      assertThat(first.rows()).isNotEmpty().allSatisfy(row -> {
        assertThat(row.targetSchema()).isEqualTo(kind.targetSchema());
        assertThat(kind.targetTables()).contains(row.targetTable());
        assertThat(row.values()).doesNotContainKey("_kind");
      });
    }
  }

  @Test
  void unknownPayloadFieldsAndSchemaDriftFailClosedWithoutEchoingValues() throws IOException {
    var kind = loadCatalog().kinds().getFirst();
    var transformer = MigrationTransformerRegistry.from(loadCatalog()).require(kind.sourceKind());
    var payload = representativePayload(kind);
    payload.put("unknownSecretField", "must-not-leak");

    assertThatThrownBy(() -> transformer.transform(new MigrationSourceDocument(
        kind.sourceKind(), kind.sourceSchemaVersion(), "id", payload)))
        .isInstanceOf(MigrationTransformationException.class)
        .hasMessage("PostgreSQL migration source document is invalid.")
        .hasMessageNotContaining("must-not-leak");
    assertThatThrownBy(() -> transformer.transform(new MigrationSourceDocument(
        kind.sourceKind(), kind.sourceSchemaVersion() + 1, "id", representativePayload(kind))))
        .isInstanceOf(MigrationTransformationException.class);
  }

  @Test
  void everyComplexProductionOwnerRejectsUnconsumedNestedLeaves() throws IOException {
    var catalog = loadCatalog();
    var registry = MigrationTransformerRegistry.from(catalog);
    var recordConversions = Set.of(
        "record-flattened", "preserve-ledger", "vin-response-flattened",
        "record-child", "record-list-child");

    for (var kind : catalog.kinds()) {
      for (var mappingEntry : kind.fieldMappings().entrySet()) {
        if (!recordConversions.contains(mappingEntry.getValue().conversion())) {
          continue;
        }
        var payload = representativePayload(kind);
        var field = mappingEntry.getKey();
        var value = payload.get(field);
        if (value instanceof Map<?, ?> raw) {
          var unexpected = new LinkedHashMap<String, Object>();
          raw.forEach((key, nested) -> unexpected.put(key.toString(), nested));
          unexpected.put("unconsumedSecretLeaf", "must-not-leak");
          payload.put(field, unexpected);
        } else if (value instanceof List<?> values && !values.isEmpty()
            && values.getFirst() instanceof Map<?, ?> raw) {
          var unexpected = new LinkedHashMap<String, Object>();
          raw.forEach((key, nested) -> unexpected.put(key.toString(), nested));
          unexpected.put("unconsumedSecretLeaf", "must-not-leak");
          payload.put(field, List.of(unexpected));
        } else {
          throw new AssertionError("Missing complex fixture for " + kind.sourceKind() + "." + field);
        }

        try {
          registry.require(kind.sourceKind()).transform(new MigrationSourceDocument(
              kind.sourceKind(), kind.sourceSchemaVersion(), "id", payload));
          throw new AssertionError("Accepted unconsumed leaf for " + kind.sourceKind() + "." + field);
        } catch (MigrationTransformationException failure) {
          assertThat(failure).hasMessageNotContaining("must-not-leak");
        }
      }
    }
  }

  static LinkedHashMap<String, Object> representativePayload(
      PostgresqlMigrationCatalog.Kind kind) {
    var payload = new LinkedHashMap<String, Object>();
    kind.fieldMappings().forEach((field, mapping) ->
        payload.put(field, representative(kind.sourceKind() + "." + field, mapping)));
    return payload;
  }

  private static Object representative(
      String mappingKey, PostgresqlMigrationCatalog.FieldMapping mapping) {
    return switch (mapping.conversion()) {
      case "constant-kind" -> "ignored-envelope-kind";
      case "string" -> "Café 🛰";
      case "uuid-string" -> UUID.fromString("00000000-0000-0000-0000-000000000006");
      case "enum-name" -> productionEnum(mappingKey);
      case "instant-utc" -> Instant.parse("2026-08-14T00:00:00.123456789Z");
      case "local-date" -> LocalDate.parse("2026-08-14");
      case "year-month-first-day" -> "2026-08";
      case "integer" -> 7;
      case "long" -> 9_007_199_254_740_991L;
      case "boolean" -> true;
      case "decimal-12-2" -> new BigDecimal("9999999999.99");
      case "decimal-20-9" -> new BigDecimal("99999999999.123456789");
      case "double" -> 123.5d;
      case "byte-array" -> new byte[] {0, 1, -1};
      case "record-flattened", "vin-response-flattened", "record-child", "preserve-ledger" ->
          productionRecord(mappingKey);
      case "record-list-child" -> productionRecords(mappingKey);
      case "string-list-child" -> List.of("duplicate", "duplicate", "é");
      case "string-set-child" -> new LinkedHashSet<>(List.of("first", "second"));
      case "string-map-child" -> productionStringMap(mappingKey);
      default -> throw new AssertionError(mapping.conversion());
    };
  }

  private static Map<String, Object> productionStringMap(String key) {
    if ("upload_session.chunkLengths".equals(key)) {
      return new LinkedHashMap<>(Map.of("b", 2L, "a", 1L));
    }
    return new LinkedHashMap<>(Map.of("b", "two", "a", "one"));
  }

  private static String productionEnum(String key) {
    return switch (key) {
      case "account.role", "browser_session.role" -> "USER";
      case "account_trust_relationship.type" -> "BLOCK";
      case "notification.notificationType" -> "MESSAGE";
      case "post_report.status" -> "OPEN";
      case "federation_delivery_job.state" -> "PENDING";
      case "vote.vote" -> "UP";
      default -> "ACTIVE";
    };
  }

  private static Object productionRecord(String key) {
    return switch (key) {
      case "account.federationIdentity" -> Map.of(
          "actorId", "actor", "keyId", "key", "publicKeyPem", "pem",
          "encryptedPrivateKey", Map.of("nonce", new byte[12], "ciphertext", new byte[16]),
          "keyVersion", 1, "createdOn", Instant.parse("2026-08-14T00:00:00Z"));
      case "account.pendingModerationAudit", "post_report.pendingModerationAudit" -> Map.ofEntries(
          Map.entry("eventId", "event"), Map.entry("actorAccountId", "account"),
          Map.entry("actorUsername", "owner"), Map.entry("action", "UPDATE"),
          Map.entry("targetType", "ACCOUNT"), Map.entry("targetId", "target"),
          Map.entry("targetLabel", "label"), Map.entry("reason", "reason"),
          Map.entry("message", "message"), Map.entry("beforeValues", Map.of("a", "b")),
          Map.entry("afterValues", Map.of("a", "c")),
          Map.entry("metadata", Map.of("ticket", "one")));
      case "post_link_preview_cache.preview" -> linkPreview();
      case "music_runtime_state.queue" -> Map.of("entries", List.of(queueEntry("one")));
      case "music_runtime_state.radio" -> Map.of(
          "stationSequence", 1L, "trackId", "track", "observedToken", "token",
          "startedAt", Instant.parse("2026-08-14T00:00:00Z"), "durationSeconds", 3.5,
          "source", "QUEUE", "queueEntryId", "entry");
      case "restaurant.address" -> Map.ofEntries(
          Map.entry("city", "Austin"), Map.entry("county", "Travis"),
          Map.entry("country", "US"), Map.entry("latitude", 30.1),
          Map.entry("longitude", -97.1), Map.entry("postalCode", "78701"),
          Map.entry("state", "TX"), Map.entry("street1", "1 Main"),
          Map.entry("street2", "Suite 1"));
      case "import_state.lastResult" -> Map.of(
          "source", "OSM", "fetched", 6, "imported", 5, "updated", 4,
          "skippedExisting", 3, "skippedInvalid", 2);
      case "import_preview.counts" -> Map.of(
          "fetched", 6, "created", 5, "updated", 4, "deleted", 3,
          "unchanged", 2, "invalid", 1);
      case "vin_decode_cache.response" -> Map.ofEntries(
          Map.entry("vin", "JM1TEST"), Map.entry("make", "Mazda"),
          Map.entry("model", "3"), Map.entry("year", 2019), Map.entry("body", "Hatchback"),
          Map.entry("plantCity", "Hofu"), Map.entry("plantState", "Yamaguchi"),
          Map.entry("plantCountry", "Japan"), Map.entry("errorCode", "0"),
          Map.entry("errorText", ""), Map.entry("rawDecodedValues", Map.of("Make", "Mazda")));
      case "random_vin_import_state.robotsPolicy" -> Map.of(
          "checkedOn", Instant.parse("2026-08-14T00:00:00Z"), "allowed", true,
          "reason", "allowed", "failClosed", false);
      case "zip_import_state.result" -> Map.ofEntries(
          Map.entry("processed", 6), Map.entry("created", 5), Map.entry("updated", 4),
          Map.entry("unchanged", 3), Map.entry("deleted", 2), Map.entry("source", "Census"),
          Map.entry("sourceYear", 2026), Map.entry("checksum", "a".repeat(64)),
          Map.entry("importedOn", Instant.parse("2026-08-14T00:00:00Z")),
          Map.entry("noOp", false));
      default -> throw new AssertionError("Missing production record fixture for " + key);
    };
  }

  private static List<?> productionRecords(String key) {
    var value = switch (key) {
      case "post.editAudit" -> Map.of(
          "editorAccountId", "account", "beforeText", "before", "afterText", "after",
          "editedOn", Instant.parse("2026-08-14T00:00:00Z"));
      case "post.topics" -> Map.of("canonical", "java", "display", "Java");
      case "post.linkPreviews" -> linkPreview();
      case "session.restaurantResetAudit" -> Map.of(
          "revision", 1L, "accountId", "account", "username", "owner",
          "restaurantIds", List.of("restaurant"),
          "occurredOn", Instant.parse("2026-08-14T00:00:00Z"));
      case "radio_state.knownDurations" -> Map.of(
          "path", "music/file.mp3", "observedToken", "token", "durationSeconds", 3.5);
      case "price_snapshot.metroPrices" -> Map.ofEntries(
          Map.entry("metroName", "Austin"), Map.entry("city", "Austin"),
          Map.entry("state", "TX"), Map.entry("restaurantRef", "ref"),
          Map.entry("restaurantName", "Canes"), Map.entry("address", "1 Main"),
          Map.entry("sourceUrl", "https://example.test"), Map.entry("price", BigDecimal.ONE),
          Map.entry("currency", "USD"), Map.entry("status", "VERIFIED"),
          Map.entry("sourceName", "source"), Map.entry("qualityStatus", "VERIFIED"),
          Map.entry("confidenceLevel", "HIGH"), Map.entry("rawResponseHash", "a".repeat(64)),
          Map.entry("matchedItemName", "Box"), Map.entry("failureReason", "none"),
          Map.entry("reviewNote", "review"),
          Map.entry("collectedOn", Instant.parse("2026-08-14T00:00:00Z")),
          Map.entry("sourceFetchedOn", Instant.parse("2026-08-14T00:00:00Z")),
          Map.entry("reviewedOn", Instant.parse("2026-08-14T00:00:00Z")));
      case "domain_collection_cutover.expectedKindMetrics" ->
          Map.of("kind", "account", "count", 1L, "checksum", "a".repeat(64));
      default -> throw new AssertionError("Missing production list fixture for " + key);
    };
    return List.of(value, value);
  }

  private static Map<String, Object> linkPreview() {
    return Map.of("url", "https://example.test", "domain", "example.test", "title", "Title",
        "description", "Description", "imageUrl", "https://example.test/image.png");
  }

  private static Map<String, Object> queueEntry(String id) {
    return Map.of("id", id, "trackId", "track", "observedToken", "token",
        "enqueuedByAccountId", "account",
        "enqueuedAt", Instant.parse("2026-08-14T00:00:00Z"));
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MigrationTransformerAllKindsTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}

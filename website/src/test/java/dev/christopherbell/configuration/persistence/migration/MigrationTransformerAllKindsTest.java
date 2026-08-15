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

  @Test
  void everyDeclaredRequiredComplexLeafRejectsExplicitNull() throws IOException {
    var catalog = loadCatalog();
    var registry = MigrationTransformerRegistry.from(catalog);
    for (var kind : catalog.kinds()) {
      for (var mappingEntry : kind.fieldMappings().entrySet()) {
        var field = mappingEntry.getKey();
        var mapping = mappingEntry.getValue();
        for (var requiredPath : mapping.requiredFields()) {
          var payload = representativePayload(kind);
          payload.put(field, nullRequiredPath(payload.get(field), requiredPath));
          try {
            registry.require(kind.sourceKind()).transform(new MigrationSourceDocument(
                kind.sourceKind(), kind.sourceSchemaVersion(), "null-required", payload));
            throw new AssertionError("Accepted null required leaf "
                + kind.sourceKind() + "." + field + "." + requiredPath);
          } catch (MigrationTransformationException expected) {
            assertThat(expected).hasMessage("PostgreSQL migration source document is invalid.");
          }
        }
      }
    }
  }

  @Test
  void everyDeclaredRequiredComplexLeafHasCatalogDrivenRemovalCoverage() throws IOException {
    var catalog = loadCatalog();
    var registry = MigrationTransformerRegistry.from(catalog);
    var terminalRemovals = 0;
    var nestedRemovals = 0;
    var listRemovals = 0;
    var mapRemovals = 0;
    for (var kind : catalog.kinds()) {
      for (var mappingEntry : kind.fieldMappings().entrySet()) {
        var field = mappingEntry.getKey();
        var mapping = mappingEntry.getValue();
        for (var requiredPath : mapping.requiredFields()) {
          var payload = representativePayload(kind);
          if (Set.of("$item", "$key", "$value").contains(requiredPath)) {
            var removed = removePseudoPath(payload.get(field));
            payload.put(field, removed.value());
            if (kind.sourceKind().equals("upload_session")
                && Set.of("chunkDigests", "chunkLengths").contains(field)) {
              var companion = field.equals("chunkDigests") ? "chunkLengths" : "chunkDigests";
              var values = new LinkedHashMap<>((Map<String, Object>) payload.get(companion));
              values.remove(removed.mapKey());
              payload.put(companion, values);
            }
            if (requiredPath.equals("$item")) {
              listRemovals++;
            } else {
              mapRemovals++;
            }
            registry.require(kind.sourceKind()).transform(new MigrationSourceDocument(
                kind.sourceKind(), kind.sourceSchemaVersion(), "removed-owner-leaf", payload));
            continue;
          }
          payload.put(field, removeRequiredPath(payload.get(field), requiredPath));
          if (requiredPath.contains(".") || requiredPath.contains("[]")) {
            nestedRemovals++;
          } else {
            terminalRemovals++;
          }
          assertThatThrownBy(() -> registry.require(kind.sourceKind()).transform(
              new MigrationSourceDocument(
                  kind.sourceKind(), kind.sourceSchemaVersion(), "removed-required", payload)))
              .as(kind.sourceKind() + "." + field + "." + requiredPath)
              .isInstanceOf(MigrationTransformationException.class)
              .hasMessage("PostgreSQL migration source document is invalid.");
        }
      }
    }
    assertThat(terminalRemovals).isPositive();
    assertThat(nestedRemovals).isPositive();
    assertThat(listRemovals).isPositive();
    assertThat(mapRemovals).isPositive();
  }

  @Test
  void everyDeclaredComplexInvariantRejectsItsAdversarialCounterexample() throws IOException {
    var catalog = loadCatalog();
    var registry = MigrationTransformerRegistry.from(catalog);
    for (var kind : catalog.kinds()) {
      for (var mappingEntry : kind.fieldMappings().entrySet()) {
        for (var invariant : mappingEntry.getValue().invariants()) {
          var payload = representativePayload(kind);
          payload.put(mappingEntry.getKey(), violateInvariant(payload.get(mappingEntry.getKey()),
              invariant));
          assertThatThrownBy(() -> registry.require(kind.sourceKind()).transform(
              new MigrationSourceDocument(
                  kind.sourceKind(), kind.sourceSchemaVersion(), "invalid-invariant", payload)))
              .as(kind.sourceKind() + "." + mappingEntry.getKey() + ":" + invariant)
              .isInstanceOf(MigrationTransformationException.class);
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

  private static Object nullRequiredPath(Object original, String path) {
    if (path.equals("$item")) {
      var values = new java.util.ArrayList<>((java.util.Collection<?>) original);
      values.set(0, null);
      return values;
    }
    if (path.equals("$key") || path.equals("$value")) {
      var values = new LinkedHashMap<>((Map<String, Object>) original);
      var first = values.entrySet().iterator().next();
      values.remove(first.getKey());
      values.put(path.equals("$key") ? null : first.getKey(),
          path.equals("$value") ? null : first.getValue());
      return values;
    }
    var copy = deepMutableCopy(original);
    if (copy instanceof java.util.List<?> records) {
      records.forEach(record -> nullPath((Map<String, Object>) record, path));
    } else {
      nullPath((Map<String, Object>) copy, path);
    }
    return copy;
  }

  private static Object removeRequiredPath(Object original, String path) {
    var copy = deepMutableCopy(original);
    if (copy instanceof java.util.List<?> records) {
      records.forEach(record -> removePath((Map<String, Object>) record, path));
    } else {
      removePath((Map<String, Object>) copy, path);
    }
    return copy;
  }

  private static RemovedPseudoPath removePseudoPath(Object original) {
    if (original instanceof java.util.Collection<?> collection) {
      var values = new java.util.ArrayList<>(collection);
      values.removeFirst();
      return new RemovedPseudoPath(values, null);
    }
    var values = new LinkedHashMap<>((Map<String, Object>) original);
    var key = values.keySet().iterator().next();
    values.remove(key);
    return new RemovedPseudoPath(values, key);
  }

  private static Object deepMutableCopy(Object value) {
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, nested) -> result.put(key.toString(), deepMutableCopy(nested)));
      return result;
    }
    if (value instanceof java.util.Collection<?> collection) {
      return collection.stream().map(MigrationTransformerAllKindsTest::deepMutableCopy)
          .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static Object violateInvariant(Object original, String invariant) {
    var copy = deepMutableCopy(original);
    return switch (invariant) {
      case "string-items" -> new java.util.ArrayList<>(List.of(42));
      case "unique-map-keys" -> {
        var values = (Map<String, Object>) copy;
        var value = values.values().iterator().next();
        values.clear();
        values.put("", value);
        yield values;
      }
      case "encrypted-key-bytes" -> {
        var values = (Map<String, Object>) copy;
        ((Map<String, Object>) values.get("encryptedPrivateKey")).put("nonce", new byte[11]);
        yield values;
      }
      case "metadata-alias-exclusive" -> {
        var values = (Map<String, Object>) copy;
        values.put("metadataValues", Map.of("conflict", "true"));
        yield values;
      }
      case "restaurant-id-order" -> {
        var records = (java.util.List<Map<String, Object>>) copy;
        records.getFirst().put("restaurantIds", List.of("duplicate", "duplicate"));
        yield records;
      }
      case "raw-values-scalar" -> {
        var values = (Map<String, Object>) copy;
        values.put("rawDecodedValues", Map.of("unsafe", Map.of("nested", "value")));
        yield values;
      }
      case "fail-closed-reason" -> {
        var values = (Map<String, Object>) copy;
        values.put("reason", "");
        yield values;
      }
      case "coordinate-pair" -> {
        var values = (Map<String, Object>) copy;
        values.put("latitude", 91);
        yield values;
      }
      case "queue-entry-id-unique" -> {
        var values = (Map<String, Object>) copy;
        var entries = (java.util.List<Object>) values.get("entries");
        entries.add(deepMutableCopy(entries.getFirst()));
        yield values;
      }
      case "positive-duration" -> {
        setFirstNumericField(copy, "durationSeconds", 0);
        yield copy;
      }
      case "nonnegative-counts" -> {
        setFirstNumericField(copy, null, -1);
        yield copy;
      }
      case "nonnegative-price" -> {
        setFirstNumericField(copy, "price", -1);
        yield copy;
      }
      default -> throw new AssertionError("Missing invariant counterexample: " + invariant);
    };
  }

  @SuppressWarnings("unchecked")
  private static void setFirstNumericField(Object value, String preferred, Number replacement) {
    var record = value instanceof java.util.List<?> records
        ? (Map<String, Object>) records.getFirst() : (Map<String, Object>) value;
    if (preferred != null) {
      record.put(preferred, replacement);
      return;
    }
    var key = record.entrySet().stream()
        .filter(entry -> entry.getValue() instanceof Number)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
    record.put(key, replacement);
  }

  @SuppressWarnings("unchecked")
  private static void nullPath(Map<String, Object> values, String path) {
    var dot = path.indexOf('.');
    var segment = dot < 0 ? path : path.substring(0, dot);
    var list = segment.endsWith("[]");
    var key = list ? segment.substring(0, segment.length() - 2) : segment;
    if (dot < 0) {
      values.put(key, null);
      return;
    }
    var remainder = path.substring(dot + 1);
    if (list) {
      for (var element : (java.util.List<Object>) values.get(key)) {
        nullPath((Map<String, Object>) element, remainder);
      }
    } else {
      nullPath((Map<String, Object>) values.get(key), remainder);
    }
  }

  @SuppressWarnings("unchecked")
  private static void removePath(Map<String, Object> values, String path) {
    var dot = path.indexOf('.');
    var segment = dot < 0 ? path : path.substring(0, dot);
    var list = segment.endsWith("[]");
    var key = list ? segment.substring(0, segment.length() - 2) : segment;
    if (dot < 0) {
      values.remove(key);
      return;
    }
    var remainder = path.substring(dot + 1);
    if (list) {
      for (var element : (java.util.List<Object>) values.get(key)) {
        removePath((Map<String, Object>) element, remainder);
      }
    } else {
      removePath((Map<String, Object>) values.get(key), remainder);
    }
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = MigrationTransformerAllKindsTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }

  private record RemovedPseudoPath(Object value, String mapKey) {}
}

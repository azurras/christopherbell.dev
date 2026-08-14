package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;

class CatalogDocumentTransformerProductionShapeTest {
  private static final Instant NOW = Instant.parse("2026-08-14T10:30:00Z");

  @Test
  void accountMapsNestedEncryptedFederationKeyAndModerationPartitionsExplicitly()
      throws IOException {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("email", "Owner@Example.test");
    payload.put("role", "USER");
    payload.put("status", "ACTIVE");
    payload.put("username", "owner");
    payload.put("federationIdentity", Map.of(
        "actorId", "https://example.test/users/owner",
        "keyId", "https://example.test/users/owner#main-key",
        "publicKeyPem", "public-key",
        "encryptedPrivateKey", Map.of(
            "nonce", new Binary(new byte[12]),
            "ciphertext", new Binary(new byte[16])),
        "keyVersion", 3,
        "createdOn", NOW));
    payload.put("pendingModerationAudit", moderationAudit());

    var transformed = transform("account", "account-1", payload);

    assertThat(rows(transformed, "account_federation_identity")).singleElement()
        .satisfies(row -> assertThat(row.values())
            .containsEntry("private_key_nonce", new byte[12])
            .containsEntry("private_key_ciphertext", new byte[16]));
    assertThat(rows(transformed, "account_moderation_audit_value"))
        .extracting(row -> List.of(
            row.values().get("partition_name"),
            row.values().get("value_key"),
            row.values().get("value")))
        .containsExactly(
            List.of("before", "status", "PENDING"),
            List.of("after", "status", "ACTIVE"),
            List.of("metadata", "ticket", "ABC-1"));
  }

  @Test
  void runtimeQueueExpandsTheProductionEntriesCollectionInOrder() throws IOException {
    var payload = Map.<String, Object>of(
        "kind", "QUEUE",
        "queue", Map.of("entries", List.of(
            queueEntry("queue-1", "track-1", NOW),
            queueEntry("queue-2", "track-2", NOW.plusSeconds(1)))),
        "version", 4L);

    var transformed = transform("music_runtime_state", "music-queue", payload);

    assertThat(rows(transformed, "queue_entry"))
        .extracting(row -> List.of(
            row.values().get("ordinal"),
            row.values().get("queue_entry_id"),
            row.values().get("track_id")))
        .containsExactly(List.of(0, "queue-1", "track-1"), List.of(1, "queue-2", "track-2"));
  }

  @Test
  void runtimeQueueRejectsEveryMissingRequiredEntryLeaf() throws IOException {
    for (var missing : List.of(
        "id", "trackId", "observedToken", "enqueuedByAccountId", "enqueuedAt")) {
      var entry = new LinkedHashMap<String, Object>(queueEntry("queue-1", "track-1", NOW));
      entry.remove(missing);
      assertThatThrownBy(() -> transform("music_runtime_state", "queue-missing-" + missing,
          Map.of("kind", "QUEUE", "queue", Map.of("entries", List.of(entry)), "version", 1L)))
          .as(missing)
          .isInstanceOf(MigrationTransformationException.class);
    }
  }

  @Test
  void declaredComplexCrossFieldInvariantsRejectDuplicateQueueIdsAndNegativeCounts()
      throws IOException {
    assertThatThrownBy(() -> transform("music_runtime_state", "queue-duplicate", Map.of(
        "kind", "QUEUE", "queue", Map.of("entries", List.of(
            queueEntry("duplicate", "track-1", NOW),
            queueEntry("duplicate", "track-2", NOW.plusSeconds(1)))), "version", 1L)))
        .isInstanceOf(MigrationTransformationException.class);

    assertThatThrownBy(() -> transform("import_preview", "negative-count", Map.of(
        "actorAccountId", "account-1", "createdOn", NOW, "expiresOn", NOW.plusSeconds(60),
        "counts", Map.of("fetched", 1, "created", -1, "updated", 0, "deleted", 0,
            "unchanged", 0, "invalid", 0))))
        .isInstanceOf(MigrationTransformationException.class);
  }

  @Test
  void lunchResetAuditExpandsNestedRestaurantsWithBothOrdinals() throws IOException {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("createdByAccountId", "account-1");
    payload.put("createdByUsername", "owner");
    payload.put("participantAccountIds", List.of("account-1"));
    payload.put("participantUsernamesByAccountId", Map.of("account-1", "owner"));
    payload.put("restaurantIds", List.of("restaurant-1", "restaurant-2"));
    payload.put("votesByAccountId", Map.of("account-1", "restaurant-1"));
    payload.put("revision", 7L);
    payload.put("activeUntil", NOW.plusSeconds(3_600));
    payload.put("deleteOn", NOW.plusSeconds(7_200));
    payload.put("restaurantResetCount", 1L);
    payload.put("restaurantResetAudit", List.of(Map.of(
        "revision", 6L,
        "accountId", "account-1",
        "username", "owner",
        "restaurantIds", List.of("restaurant-2", "restaurant-1"),
        "occurredOn", NOW)));
    payload.put("createdOn", NOW.minusSeconds(60));
    payload.put("lastUpdatedOn", NOW);

    var transformed = transform("session", "session-1", payload);

    assertThat(rows(transformed, "lunch_session_reset_restaurant"))
        .extracting(row -> List.of(
            row.values().get("reset_ordinal"),
            row.values().get("restaurant_ordinal"),
            row.values().get("restaurant_id")))
        .containsExactly(
            List.of(0, 0, "restaurant-2"),
            List.of(0, 1, "restaurant-1"));
  }

  @Test
  void uploadChunkMapsMergeByChunkKeyInsteadOfOrdinalGuessing() throws IOException {
    var payload = uploadPayload();
    payload.put("chunkDigests", new LinkedHashMap<>(Map.of(
        "chunk-b", "b".repeat(64), "chunk-a", "a".repeat(64))));
    payload.put("chunkLengths", new LinkedHashMap<>(Map.of(
        "chunk-a", 11L, "chunk-b", 22L)));

    var transformed = transform("upload_session", "upload-1", payload);

    assertThat(rows(transformed, "upload_chunk"))
        .extracting(row -> List.of(
            row.values().get("chunk_key"),
            row.values().get("digest"),
            row.values().get("chunk_length")))
        .containsExactly(
            List.of("chunk-a", "a".repeat(64), 11L),
            List.of("chunk-b", "b".repeat(64), 22L));

    var mismatched = uploadPayload();
    mismatched.put("chunkDigests", Map.of("chunk-a", "a".repeat(64)));
    mismatched.put("chunkLengths", Map.of("chunk-b", 22L));
    assertThatThrownBy(() -> transform("upload_session", "upload-2", mismatched))
        .isInstanceOf(MigrationTransformationException.class);
  }

  @Test
  void adminMapPartitionsRemainPresentAndHaveDistinctCompositeIdentity() throws IOException {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("actorUsername", "owner");
    payload.put("action", "ACCOUNT_UPDATE");
    payload.put("targetType", "ACCOUNT");
    payload.put("targetId", "account-1");
    payload.put("beforeValues", Map.of("status", "PENDING"));
    payload.put("afterValues", Map.of("status", "ACTIVE"));
    payload.put("metadata", Map.of("ticket", "ABC-1"));
    payload.put("createdOn", NOW);

    var transformed = transform("admin_activity", "activity-1", payload);

    assertThat(rows(transformed, "admin_activity")).singleElement()
        .satisfies(row -> assertThat(row.values())
            .containsEntry("before_values_present", true)
            .containsEntry("after_values_present", true)
            .containsEntry("metadata_present", true));
    assertThat(rows(transformed, "admin_activity_value"))
        .extracting(row -> List.of(
            row.values().get("partition_name"),
            row.values().get("value_key"),
            row.values().get("value_text")))
        .containsExactly(
            List.of("before", "status", "PENDING"),
            List.of("after", "status", "ACTIVE"),
            List.of("metadata", "ticket", "ABC-1"));
  }

  @Test
  void restaurantWorkflowPersistedYearMonthMapsExactlyToFirstDayOfMonth() throws IOException {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("lastStartedOn", NOW.minusSeconds(60));
    payload.put("lastCompletedOn", NOW);
    payload.put("lastCompletedMonth", "2026-08");
    payload.put("status", "COMPLETED");
    payload.put("trigger", "SCHEDULED");
    payload.put("actorAccountId", "account-1");

    var transformed = transform("import_state", "restaurant-import", payload);

    assertThat(rows(transformed, "restaurant_import_state")).singleElement()
        .satisfies(row -> assertThat(row.values())
            .containsEntry("last_completed_month", LocalDate.of(2026, 8, 1)));
  }

  @Test
  void scalarAndSpecialMappingsRejectWrongBsonTypesAndConflictingAliases() throws IOException {
    assertThatThrownBy(() -> transform("account", "account-wrong-string", Map.of(
        "email", new org.bson.Document("secret", "value"),
        "role", "USER", "status", "ACTIVE", "username", "owner")))
        .isInstanceOf(MigrationTransformationException.class);

    assertThatThrownBy(() -> transform("vin_decode_cache", "JM1BN1L30K1234567", Map.of(
        "response", Map.of("year", "2019"))))
        .isInstanceOf(MigrationTransformationException.class);

    var wrongEncryptedKey = new LinkedHashMap<String, Object>();
    wrongEncryptedKey.put("email", "owner@example.test");
    wrongEncryptedKey.put("role", "USER");
    wrongEncryptedKey.put("status", "ACTIVE");
    wrongEncryptedKey.put("username", "owner");
    wrongEncryptedKey.put("federationIdentity", Map.of(
        "actorId", "actor", "keyId", "key", "publicKeyPem", "pem",
        "encryptedPrivateKey", Map.of("nonce", "not-binary", "ciphertext", new byte[16]),
        "keyVersion", 1, "createdOn", NOW));
    assertThatThrownBy(() -> transform("account", "account-wrong-binary", wrongEncryptedKey))
        .isInstanceOf(MigrationTransformationException.class);

    assertThatThrownBy(() -> transform("music_runtime_state", "queue-wrong-time", Map.of(
        "kind", "QUEUE", "version", 1L,
        "queue", Map.of("entries", List.of(Map.of(
            "id", "queue-1", "trackId", "track-1", "observedToken", "token",
            "enqueuedByAccountId", "account-1", "enqueuedAt", "2026-08-14"))))))
        .isInstanceOf(MigrationTransformationException.class);

    var audit = new LinkedHashMap<String, Object>(moderationAudit());
    audit.put("metadataValues", Map.of("ticket", "conflict"));
    assertThatThrownBy(() -> transform("account", "account-alias-conflict", Map.of(
        "email", "owner@example.test", "role", "USER", "status", "ACTIVE",
        "username", "owner", "pendingModerationAudit", audit)))
        .isInstanceOf(MigrationTransformationException.class);
  }

  private static Map<String, Object> moderationAudit() {
    return Map.ofEntries(
        Map.entry("eventId", "event-1"),
        Map.entry("actorAccountId", "account-1"),
        Map.entry("actorUsername", "owner"),
        Map.entry("action", "ACCOUNT_UPDATE"),
        Map.entry("targetType", "ACCOUNT"),
        Map.entry("targetId", "account-1"),
        Map.entry("targetLabel", "owner"),
        Map.entry("reason", "approved"),
        Map.entry("message", "updated"),
        Map.entry("beforeValues", Map.of("status", "PENDING")),
        Map.entry("afterValues", Map.of("status", "ACTIVE")),
        Map.entry("metadata", Map.of("ticket", "ABC-1")));
  }

  private static Map<String, Object> queueEntry(String id, String trackId, Instant enqueuedAt) {
    return Map.of(
        "id", id,
        "trackId", trackId,
        "observedToken", "token-" + id,
        "enqueuedByAccountId", "account-1",
        "enqueuedAt", enqueuedAt);
  }

  private static LinkedHashMap<String, Object> uploadPayload() {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("version", 1L);
    payload.put("ownerId", "account-1");
    payload.put("parentPath", "folder");
    payload.put("name", "upload.bin");
    payload.put("expectedBytes", 33L);
    payload.put("expectedSha256", "c".repeat(64));
    payload.put("nextOffset", 33L);
    payload.put("stagingKey", "staging-key");
    payload.put("expiresAt", NOW.plusSeconds(3_600));
    payload.put("state", "ACTIVE");
    payload.put("createdAt", NOW.minusSeconds(60));
    payload.put("updatedAt", NOW);
    return payload;
  }

  private static TransformedMigrationDocument transform(
      String sourceKind, String sourceId, Map<String, Object> payload) throws IOException {
    var catalog = loadCatalog();
    return MigrationTransformerRegistry.from(catalog).require(sourceKind).transform(
        new MigrationSourceDocument(sourceKind, 1, sourceId, payload));
  }

  private static List<MigrationRelationalRow> rows(
      TransformedMigrationDocument transformed, String table) {
    return transformed.rows().stream().filter(row -> row.targetTable().equals(table)).toList();
  }

  private static PostgresqlMigrationCatalog loadCatalog() throws IOException {
    try (var input = CatalogDocumentTransformerProductionShapeTest.class.getClassLoader()
        .getResourceAsStream("db/migration/postgresql-migration-catalog.yml")) {
      assertThat(input).isNotNull();
      return new PostgresqlMigrationCatalogLoader().load(input);
    }
  }
}

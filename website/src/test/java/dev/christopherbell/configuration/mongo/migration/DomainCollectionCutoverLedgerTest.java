package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class DomainCollectionCutoverLedgerTest {
  private final MongoTemplate mongo = mock(MongoTemplate.class);

  @Test
  void acceptsOnlyCompletedTargetActiveLedgerForExactDigest() {
    when(mongo.findOne(any(), eq(Document.class), eq("application_migrations")))
        .thenReturn(envelope("TARGET_ACTIVE", true, DomainCollectionManifest.DIGEST));

    assertThatCode(() -> new DomainCollectionCutoverLedger(mongo)
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
      assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo)
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

    assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo)
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
      assertThatThrownBy(() -> new DomainCollectionCutoverLedger(mongo).requireTargetActive())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Domain collection schema is not active.");
    }
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
}

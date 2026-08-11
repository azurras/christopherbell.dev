package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
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

  private static Document envelope(String state, boolean completed, String digest) {
    return new Document("_id", new Document("kind", "domain_collection_cutover")
        .append("legacyId", DomainCollectionCutoverLedger.LEGACY_ID))
        .append("_kind", "domain_collection_cutover")
        .append("schemaVersion", 1)
        .append("payload", new Document("state", state)
            .append("manifestDigest", digest)
            .append("completed", completed)
            .append("revision", 7));
  }
}

package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class NamespacedMongoIdTest {
  @Test
  void writesKindThenLegacyIdWithoutChangingTheBsonIdentityType() {
    var legacyId = new ObjectId();

    var bson = NamespacedMongoId.of("sample_kind", legacyId).toBson();

    assertThat(bson.keySet()).containsExactly("kind", "legacyId");
    assertThat(bson.get("legacyId")).isSameAs(legacyId);
  }

  @Test
  void readsOnlyTheExactCanonicalIdentityShapeForTheExpectedKind() {
    var legacyId = new Document("tenant", 42L).append("sequence", 7);
    var bson = new Document("kind", "sample_kind").append("legacyId", legacyId);

    var identity = NamespacedMongoId.require(bson, "sample_kind");

    assertThat(identity.legacyId()).isSameAs(legacyId);
    assertThat(identity.toBson()).isEqualTo(bson);
  }

  @Test
  void rejectsReorderedWrongKindOrExpandedIdentitiesWithoutLeakingValues() {
    var reordered = new Document("legacyId", "secret-id").append("kind", "sample_kind");
    var wrongKind = new Document("kind", "other_kind").append("legacyId", "secret-id");
    var expanded = new Document("kind", "sample_kind")
        .append("legacyId", "secret-id")
        .append("extra", true);

    for (var malformed : List.of(reordered, wrongKind, expanded)) {
      assertThatThrownBy(() -> NamespacedMongoId.require(malformed, "sample_kind"))
          .isInstanceOf(MalformedDomainDocumentException.class)
          .hasMessage("Mongo domain document is malformed.")
          .hasMessageNotContaining("secret-id")
          .hasMessageNotContaining("sample_kind")
          .hasMessageNotContaining("other_kind");
    }
  }
}

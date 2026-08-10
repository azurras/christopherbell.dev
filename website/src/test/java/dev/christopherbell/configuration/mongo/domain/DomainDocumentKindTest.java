package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class DomainDocumentKindTest {
  @ParameterizedTest
  @MethodSource("approvedCollections")
  void acceptsEveryApprovedTargetCollection(String collection) {
    var metadata = new DomainDocumentKind<>(collection, "sample_kind", 1, String.class);

    assertThat(metadata.collection()).isEqualTo(collection);
  }

  @Test
  void rejectsACollectionOutsideTheConsolidatedCatalog() {
    assertThatThrownBy(() -> new DomainDocumentKind<>(
        "legacy_accounts", "sample_kind", 1, String.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo collection and kind must be canonical.");
  }

  @Test
  void rejectsNoncanonicalKindAndNonpositiveSchema() {
    assertThatThrownBy(() -> new DomainDocumentKind<>(
        "content", "Post", 1, String.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo collection and kind must be canonical.");
    assertThatThrownBy(() -> new DomainDocumentKind<>(
        "content", "post", 0, String.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo schema version must be positive.");
  }

  private static Stream<String> approvedCollections() {
    return Stream.of(
        "accounts",
        "sessions",
        "communications",
        "content",
        "federation",
        "music",
        "whatsforlunch",
        "shared_folder",
        "vehicles",
        "location",
        "canes_box_tracker",
        "application_runtime",
        "application_migrations",
        "admin_activity");
  }
}

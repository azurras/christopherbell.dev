package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DomainDocumentKindTest {
  @ParameterizedTest
  @MethodSource("approvedAssociations")
  void acceptsEveryApprovedTargetCollection(String collection, String kind) {
    var registry = DomainDocumentKindRegistry.of(Map.of(kind, collection));

    var metadata = registry.require(kind, 1, String.class);

    assertThat(metadata.collection()).isEqualTo(collection);
    assertThat(metadata.kind()).isEqualTo(kind);
  }

  @Test
  void rejectsACollectionOutsideTheConsolidatedCatalog() {
    assertThatThrownBy(() -> DomainDocumentKindRegistry.of(
        Map.of("sample_kind", "legacy_accounts")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo collection and kind approvals must be canonical.");
  }

  @Test
  void rejectsAnUnknownCanonicalLookingKind() {
    var registry = DomainDocumentKindRegistry.of(Map.of("known_kind", "content"));

    assertThatThrownBy(() -> registry.require("typo_kind", 1, String.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo domain kind is not approved.");
  }

  @Test
  void rejectsNoncanonicalKindAndNonpositiveSchema() {
    assertThatThrownBy(() -> DomainDocumentKindRegistry.of(Map.of("Post", "content")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo collection and kind approvals must be canonical.");

    var registry = DomainDocumentKindRegistry.of(Map.of("post", "content"));
    assertThatThrownBy(() -> registry.require("post", 0, String.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo schema version must be positive.");
  }

  private static Stream<Arguments> approvedAssociations() {
    return Stream.of(
        Arguments.of("accounts", "sample_accounts"),
        Arguments.of("sessions", "sample_sessions"),
        Arguments.of("communications", "sample_communications"),
        Arguments.of("content", "sample_content"),
        Arguments.of("federation", "sample_federation"),
        Arguments.of("music", "sample_music"),
        Arguments.of("whatsforlunch", "sample_whatsforlunch"),
        Arguments.of("shared_folder", "sample_shared_folder"),
        Arguments.of("vehicles", "sample_vehicles"),
        Arguments.of("location", "sample_location"),
        Arguments.of("canes_box_tracker", "sample_canes_box_tracker"),
        Arguments.of("application_runtime", "sample_application_runtime"),
        Arguments.of("application_migrations", "sample_application_migrations"),
        Arguments.of("admin_activity", "sample_admin_activity"));
  }
}

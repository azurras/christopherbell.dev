package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DomainCollectionManifestTest {
  private static final Set<String> TARGETS = Set.of(
      "accounts", "sessions", "communications", "content", "federation", "music",
      "whatsforlunch", "shared_folder", "vehicles", "location", "canes_box_tracker",
      "application_runtime", "application_migrations", "admin_activity");

  private static final List<ExpectedKind> KINDS = List.of(
      kind("accounts", "account", "accounts", "account.model.Account"),
      kind("accounts", "account_follow", "account_follows", "account.follow.AccountFollow"),
      kind("accounts", "account_trust_relationship", "account_trust_relationships",
          "account.trust.AccountTrustRelationship"),
      kind("accounts", "account_deletion_job", "account_deletion_jobs",
          "account.deletion.AccountDeletionJob"),
      kind("sessions", "browser_session", "browser_sessions",
          "configuration.security.browser.BrowserSession"),
      kind("sessions", "conversation_archive_state", "conversation_archive_states",
          "message.conversation.ConversationArchiveState"),
      kind("communications", "message", "messages", "message.model.Message"),
      kind("communications", "notification", "notifications", "notification.model.Notification"),
      kind("communications", "notification_preference", "notification_preferences",
          "notification.preference.NotificationPreference"),
      kind("communications", "notification_delivery_guard", "notification_delivery_guards",
          "notification.delivery.NotificationDeliveryGuard"),
      kind("communications", "notification_rate_limit", "notification_rate_limits",
          "notification.delivery.NotificationRateLimit"),
      kind("content", "post", "posts", "post.model.Post"),
      kind("content", "post_like", "post_likes", "post.like.PostLike"),
      kind("content", "post_report", "post_reports", "report.model.PostReport"),
      kind("content", "hidden_post_thread", "hidden_post_threads", "post.hide.HiddenPostThread"),
      kind("content", "post_link_preview_cache", "post_link_preview_cache",
          "post.preview.PostLinkPreviewCacheEntry"),
      kind("federation", "federation_scan_state", "federation_scan_state",
          "federation.outbound.FederationScanState"),
      kind("federation", "federation_delivery_job", "federation_delivery_jobs",
          "federation.outbound.FederationDeliveryJob"),
      kind("music", "music_track", "music_tracks", "music.catalog.MusicTrack"),
      kind("music", "music_playlist", "music_playlists", "music.library.MusicPlaylist"),
      kind("music", "music_metadata_edit", "music_metadata_edits",
          "music.metadata.MusicMetadataEdit"),
      kind("music", "music_runtime_state", "music_runtime_state",
          "music.radio.MusicRuntimeStateDocument"),
      kind("music", "music_radio_history", "music_radio_history",
          "music.radio.MusicRadioHistoryEvent"),
      kind("music", "music_access_attempt", "music_access_attempts",
          "music.security.MusicAccessAttempt"),
      kind("whatsforlunch", "restaurant", "whatsforlunch",
          "whatsforlunch.restaurant.model.Restaurant"),
      kind("whatsforlunch", "vote", "whatsforlunch_ratings",
          "whatsforlunch.restaurant.model.RestaurantVote"),
      kind("whatsforlunch", "favorite", "whatsforlunch_favorites",
          "whatsforlunch.restaurant.model.RestaurantFavorite"),
      kind("whatsforlunch", "preference", "whatsforlunch_preferences",
          "whatsforlunch.restaurant.model.WhatsForLunchPreference"),
      kind("whatsforlunch", "session", "whatsforlunch_sessions",
          "whatsforlunch.restaurant.model.WhatsForLunchSession"),
      kind("whatsforlunch", "daily_picks", "whatsforlunch_daily_picks",
          "whatsforlunch.restaurant.model.DailyLunchPicks"),
      kind("whatsforlunch", "import_state", "restaurant_import_state",
          "whatsforlunch.restaurant.model.RestaurantImportState"),
      kind("whatsforlunch", "import_preview", "restaurant_import_previews",
          "whatsforlunch.restaurant.importing.RestaurantImportPreviewDocument"),
      kind("shared_folder", "audit_event", "shared_folder_audit",
          "sharedfolder.audit.SharedFolderAuditEvent"),
      kind("shared_folder", "maintenance_lease", "shared_folder_maintenance_leases",
          "sharedfolder.maintenance.SharedFolderMaintenanceLeaseDocument"),
      kind("shared_folder", "media_job", "shared_folder_media_jobs",
          "sharedfolder.media.MediaJob"),
      kind("shared_folder", "mutation_recovery", "shared_folder_mutation_recoveries",
          "sharedfolder.service.SharedFolderMutationRecovery"),
      kind("shared_folder", "radio_state", "shared_folder_radio",
          "sharedfolder.radio.SharedFolderRadioDocument"),
      kind("shared_folder", "recycle_item", "shared_folder_recycle_items",
          "sharedfolder.recycle.SharedFolderRecycleItem"),
      kind("shared_folder", "upload_session", "shared_folder_upload_sessions",
          "sharedfolder.upload.SharedFolderUploadSession"),
      kind("vehicles", "vehicle", "vehicles", "vehicle.model.Vehicle"),
      kind("vehicles", "vin_decode_cache", "vehicle_vin_decode_cache",
          "vehicle.model.VehicleVinDecodeCache"),
      kind("vehicles", "nhtsa_import_state", "vehicle_import_state",
          "vehicle.nhtsa.model.NhtsaVinImportState"),
      kind("vehicles", "random_vin_import_state", "vehicle_import_state",
          "vehicle.randomvin.model.RandomVinImportState"),
      kind("location", "zip_coordinate", "location_zip_coordinates",
          "location.model.ZipCoordinate"),
      kind("location", "zip_import_state", "zip_coordinate_import_state",
          "location.model.ZipCoordinateImportState"),
      kind("canes_box_tracker", "price_snapshot", "canes_box_price_snapshots",
          "canesboxtracker.model.CanesBoxPriceSnapshot"),
      kind("application_runtime", "application_lease", "application_leases",
          "libs.mongo.lease.MongoLeaseDocument"),
      kind("application_runtime", "scheduled_collector_run", "scheduled_collector_runs",
          "libs.mongo.lease.ScheduledCollectorRun"),
      kind("application_migrations", "migration_record", "application_migrations",
          "configuration.mongo.migration.MigrationRecord"),
      kindWithoutSource("application_migrations", "domain_collection_cutover",
          "configuration.mongo.migration.DomainCollectionCutoverLedger"),
      kind("admin_activity", "admin_activity", "admin_activity", "admin.model.AdminActivity"),
      kind("admin_activity", "pending_action", "command_center_pending_actions",
          "admin.commandcenter.action.PendingActionDocument"));

  @Test
  void ownsTheExactFourteenTargetsAndFiftyTwoKinds() {
    assertThat(DomainCollectionManifest.ALL_COLLECTIONS).containsExactlyInAnyOrderElementsOf(TARGETS);
    assertThat(DomainCollectionManifest.ALL_COLLECTIONS).hasSize(14);
    assertThat(DomainCollectionManifest.ALL_KINDS)
        .extracting(definition -> new ExpectedKind(
            definition.collection(),
            definition.kind(),
            definition.legacySource(),
            definition.ownerTypeName(),
            definition.schemaVersion()))
        .containsExactlyElementsOf(KINDS);
    assertThat(DomainCollectionManifest.ALL_KINDS).hasSize(52);
  }

  @Test
  void sourceAndRuntimeTypeLookupCannotEscapeTheApprovedRegistry() throws Exception {
    assertThat(DomainCollectionManifest.forSource("vehicle_import_state"))
        .extracting(DomainCollectionManifest.KindDefinition::kind)
        .containsExactly("nhtsa_import_state", "random_vin_import_state");
    assertThat(DomainCollectionManifest.forSource("none; created by cutover")).isEmpty();
    assertThat(DomainCollectionManifest.forSource("unknown_source")).isEmpty();

    var pendingActionType = Class.forName(
        "dev.christopherbell.admin.commandcenter.action.PendingActionDocument");
    var pendingActionKind = DomainCollectionManifest.forType(pendingActionType);
    assertThat(pendingActionKind.kind()).isEqualTo("pending_action");
    assertThat(pendingActionKind.collection()).isEqualTo("admin_activity");
    assertThat(pendingActionKind.schemaVersion()).isEqualTo(1);
    assertThat(pendingActionKind.javaType()).isEqualTo(pendingActionType);

    assertThatThrownBy(() -> DomainCollectionManifest.forType(String.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Mongo domain type is not approved.");
  }

  @Test
  void freezesEveryKindScopedIndexAndTheOneGlobalIdIndexPerTarget() {
    assertThat(DomainCollectionManifest.ALL_INDEXES).hasSize(126);
    assertThat(DomainCollectionManifest.ALL_INDEXES.stream()
        .filter(index -> index.kind().isEmpty()))
        .hasSize(14)
        .allSatisfy(index -> {
          assertThat(index.name()).isEqualTo("_id_");
          assertThat(index.keys()).containsExactly(new DomainCollectionManifest.IndexKey("_id", 1));
          assertThat(index.unique()).isTrue();
          assertThat(index.partialFilterExpression()).isEmpty();
        });
    assertThat(DomainCollectionManifest.ALL_INDEXES.stream()
        .filter(index -> index.kind().isPresent()))
        .hasSize(112)
        .allSatisfy(index -> {
          assertThat(index.partialFilterExpression())
              .isEqualTo(expectedPartial(index));
          assertThat(index.keys()).allSatisfy(key -> assertThat(key.path())
              .satisfiesAnyOf(
                  path -> assertThat(path).startsWith("payload."),
                  path -> assertThat(path).isEqualTo("_id.legacyId")));
        });

    assertThat(DomainCollectionManifest.ALL_INDEXES)
        .contains(
            index("accounts", "account", "account__email_asc", true, false, null,
                key("payload.email", 1)),
            index("content", "post", "post__post_account_created_id_desc", false, false, null,
                key("payload.accountId", 1), key("payload.createdOn", -1),
                key("_id.legacyId", -1)),
            sparseSemanticIndex(
                "content", "post_report", "post_report__openDedupeKey_asc",
                "payload.openDedupeKey"),
            index("sessions", "browser_session", "browser_session__absoluteExpiresOn_asc",
                false, false, 0L, key("payload.absoluteExpiresOn", 1)),
            index("whatsforlunch", "import_preview",
                "import_preview__restaurant_import_preview_expiry", false, false, 0L,
                key("payload.expiresOn", 1)),
            index("application_runtime", "application_lease",
                "application_lease__lease_expiry", false, false, null,
                key("payload.expiresAt", 1)));
  }

  @Test
  void sparseUniqueMembershipUsesMongoCompatibleKindAndExistencePartials() {
    var translatedIndexes = Map.of(
        "post_report", "payload.openDedupeKey",
        "restaurant", "payload.normalizedName",
        "media_job", "payload.activeCacheKey");

    translatedIndexes.forEach((kind, field) -> {
      var index = DomainCollectionManifest.ALL_INDEXES.stream()
          .filter(candidate -> candidate.kind().equals(Optional.of(kind)))
          .filter(candidate -> candidate.keys().equals(List.of(key(field, 1))))
          .findFirst()
          .orElseThrow();
      assertThat(index.unique()).isTrue();
      assertThat(index.sparse()).isFalse();
      assertThat(index.partialFilterExpression()).isEqualTo(sparsePartial(kind, field));

      var absent = new java.util.HashMap<String, Object>();
      var explicitNull = new java.util.HashMap<String, Object>();
      explicitNull.put(field, null);
      var firstPresent = Map.<String, Object>of(field, "same-value");
      var duplicatePresent = Map.<String, Object>of(field, "same-value");

      assertThat(participates(index, kind, absent)).isFalse();
      assertThat(participates(index, kind, explicitNull)).isTrue();
      assertThat(conflicts(index, kind, firstPresent, kind, duplicatePresent)).isTrue();
      assertThat(conflicts(index, kind, firstPresent, "different_kind", duplicatePresent)).isFalse();
    });
  }

  @Test
  void indexNamesAreUniqueDeterministicAndMongoSafe() {
    assertThat(DomainCollectionManifest.ALL_INDEXES)
        .filteredOn(index -> index.kind().isPresent())
        .extracting(DomainCollectionManifest.IndexDefinition::name)
        .doesNotHaveDuplicates()
        .allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(120));

    var longKeys = List.of(
        new DomainCollectionManifest.IndexKey(
            "payload.an_extremely_long_field_name_used_to_force_the_canonical_index_name_limit", 1),
        new DomainCollectionManifest.IndexKey(
            "payload.another_extremely_long_field_name_used_to_force_the_canonical_index_name_limit", -1));
    var first = DomainCollectionManifest.canonicalIndexName("scheduled_collector_run", null, longKeys);
    var second = DomainCollectionManifest.canonicalIndexName("scheduled_collector_run", null, longKeys);
    assertThat(first).isEqualTo(second).hasSize(120)
        .startsWith("scheduled_collector_run__")
        .matches(".*__[0-9a-f]{16}");
    assertThat(DomainCollectionManifest.DIGEST)
        .isEqualTo("576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24");
  }

  private static ExpectedKind kind(
      String collection, String kind, String legacySource, String relativeOwnerTypeName) {
    return new ExpectedKind(
        collection,
        kind,
        Optional.of(legacySource),
        "dev.christopherbell." + relativeOwnerTypeName,
        1);
  }

  private static ExpectedKind kindWithoutSource(
      String collection, String kind, String relativeOwnerTypeName) {
    return new ExpectedKind(
        collection,
        kind,
        Optional.empty(),
        "dev.christopherbell." + relativeOwnerTypeName,
        1);
  }

  private static DomainCollectionManifest.IndexDefinition index(
      String collection,
      String kind,
      String name,
      boolean unique,
      boolean sparse,
      Long expireAfterSeconds,
      DomainCollectionManifest.IndexKey... keys) {
    return new DomainCollectionManifest.IndexDefinition(
        collection,
        Optional.of(kind),
        name,
        List.of(keys),
        unique,
        sparse,
        Map.of("_kind", kind),
        Optional.ofNullable(expireAfterSeconds),
        Optional.empty());
  }

  private static DomainCollectionManifest.IndexKey key(String path, int direction) {
    return new DomainCollectionManifest.IndexKey(path, direction);
  }

  private static DomainCollectionManifest.IndexDefinition sparseSemanticIndex(
      String collection, String kind, String name, String field) {
    return new DomainCollectionManifest.IndexDefinition(
        collection,
        Optional.of(kind),
        name,
        List.of(key(field, 1)),
        true,
        false,
        sparsePartial(kind, field),
        Optional.empty(),
        Optional.empty());
  }

  private static Map<String, Object> sparsePartial(String kind, String field) {
    return Map.of("$and", List.of(
        Map.of("_kind", kind),
        Map.of(field, Map.of("$exists", true))));
  }

  private static Map<String, Object> expectedPartial(
      DomainCollectionManifest.IndexDefinition index) {
    var kind = index.kind().orElseThrow();
    return switch (index.name()) {
      case "post_report__openDedupeKey_asc",
          "restaurant__normalizedName_asc",
          "media_job__activeCacheKey_asc" -> sparsePartial(kind, index.keys().getFirst().path());
      default -> Map.of("_kind", kind);
    };
  }

  private static boolean participates(
      DomainCollectionManifest.IndexDefinition index,
      String documentKind,
      Map<String, Object> fields) {
    var expectedKind = index.kind().orElseThrow();
    var indexedField = index.keys().getFirst().path();
    return expectedKind.equals(documentKind) && fields.containsKey(indexedField);
  }

  private static boolean conflicts(
      DomainCollectionManifest.IndexDefinition index,
      String firstKind,
      Map<String, Object> firstFields,
      String secondKind,
      Map<String, Object> secondFields) {
    var field = index.keys().getFirst().path();
    return participates(index, firstKind, firstFields)
        && participates(index, secondKind, secondFields)
        && java.util.Objects.equals(firstFields.get(field), secondFields.get(field));
  }

  private record ExpectedKind(
      String collection,
      String kind,
      Optional<String> legacySource,
      String ownerTypeName,
      int schemaVersion) {}
}

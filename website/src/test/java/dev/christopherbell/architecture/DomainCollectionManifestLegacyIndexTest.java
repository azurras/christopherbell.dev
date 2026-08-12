package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class DomainCollectionManifestLegacyIndexTest {
  private static final List<DomainCollectionManifest.IndexDefinition> MANUAL_ONLY_INDEXES =
      List.of(
          manual("application_migrations", "migration_record", "migration_status_completed",
              false, null, asc("status"), desc("completedAt")),
          manual("application_runtime", "application_lease", "lease_expiry",
              false, null, asc("expiresAt")),
          manual("whatsforlunch", "import_preview", "restaurant_import_preview_expiry",
              false, 0L, asc("expiresOn")),
          manual("whatsforlunch", "import_preview", "restaurant_import_preview_actor_created",
              false, null, asc("actorAccountId"), desc("createdOn")),
          manual("vehicles", "vin_decode_cache", "vehicle_vin_cache_expiry",
              false, 0L, asc("expiresOn")),
          manual("application_runtime", "scheduled_collector_run",
              "scheduled_collector_status_completed", false, null,
              asc("status"), desc("completedOn")),
          manual("content", "post_link_preview_cache", "post_link_preview_cache_expiry",
              false, 0L, asc("expiresOn")),
          manual("content", "post", "void_discovery_new", false, null,
              asc("parentId"), desc("createdOn"), desc("_id"), asc("expiresOn")),
          manual("content", "post", "void_discovery_fading", false, null,
              asc("parentId"), asc("expiresOn"), asc("_id")),
          manual("content", "post", "void_discovery_revived", false, null,
              asc("parentId"), desc("lastExtendedOn"), desc("_id"), asc("expiresOn")),
          manual("content", "post", "void_discovery_topic", false, null,
              asc("topics.canonical"), asc("expiresOn"), asc("rootId")),
          manual("content", "post", "void_people_active_pool", false, null,
              asc("expiresOn"), asc("accountId")),
          manual("content", "post", "void_people_authored_activity", false, null,
              asc("accountId"), asc("expiresOn"), desc("createdOn"), desc("_id")),
          manual("accounts", "account_trust_relationship", "void_people_incoming_block",
              false, null, asc("targetAccountId"), asc("type"), asc("ownerAccountId")),
          manual("accounts", "account", "federation_actor_lookup", false, null,
              asc("status"), asc("federationEnabled"), asc("username")),
          manual("content", "post", "federation_outbound_post_scan", false, null,
              asc("federationOutboundEligible"), asc("createdOn"), asc("_id")),
          manual("federation", "federation_delivery_job",
              "federation_delivery_post_peer_unique", true, null,
              asc("postId"), asc("peerName")),
          manual("federation", "federation_delivery_job", "federation_delivery_due",
              false, null, asc("state"), asc("nextAttemptOn"), asc("createdOn")),
          manual("federation", "federation_delivery_job", "federation_delivery_expired_claim",
              false, null, asc("state"), asc("claimUntil")),
          manual("accounts", "account_follow", "account_follow_target", false, null,
              asc("followedAccountId")));

  private static final List<DomainCollectionManifest.IndexDefinition>
      FROZEN_ANNOTATION_INDEXES = parseFrozen("""
          accounts|account|account__email_asc|true|false||{"_kind": "account"}||payload.email:1
          accounts|account|account__passwordResetTokenHash_asc|false|false||{"_kind": "account"}||payload.passwordResetTokenHash:1
          accounts|account|account__username_asc|true|false||{"_kind": "account"}||payload.username:1
          accounts|account_follow|account_follow__account_follow_follower_target_unique|true|false||{"_kind": "account_follow"}||payload.followerAccountId:1,payload.followedAccountId:1
          accounts|account_trust_relationship|account_trust_relationship__owner_target_type_unique|true|false||{"_kind": "account_trust_relationship"}||payload.ownerAccountId:1,payload.targetAccountId:1,payload.type:1
          accounts|account_trust_relationship|account_trust_relationship__ownerAccountId_asc|false|false||{"_kind": "account_trust_relationship"}||payload.ownerAccountId:1
          accounts|account_trust_relationship|account_trust_relationship__targetAccountId_asc|false|false||{"_kind": "account_trust_relationship"}||payload.targetAccountId:1
          sessions|browser_session|browser_session__accountId_asc|false|false||{"_kind": "browser_session"}||payload.accountId:1
          sessions|browser_session|browser_session__absoluteExpiresOn_asc|false|false|0|{"_kind": "browser_session"}||payload.absoluteExpiresOn:1
          sessions|conversation_archive_state|conversation_archive_state__conversation_archive_owner_key_unique|true|false||{"_kind": "conversation_archive_state"}||payload.ownerAccountId:1,payload.conversationKey:1
          communications|message|message__message_conversation_created_asc|false|false||{"_kind": "message"}||payload.conversationKey:1,payload.createdOn:1
          communications|message|message__message_conversation_created_id_desc|false|false||{"_kind": "message"}||payload.conversationKey:1,payload.createdOn:-1,_id.legacyId:-1
          communications|message|message__message_participant_created_desc|false|false||{"_kind": "message"}||payload.participantIds:1,payload.createdOn:-1
          communications|message|message__message_participant_created_id_desc|false|false||{"_kind": "message"}||payload.participantIds:1,payload.createdOn:-1,_id.legacyId:-1
          communications|message|message__message_recipient_sender_read|false|false||{"_kind": "message"}||payload.recipientAccountId:1,payload.senderAccountId:1,payload.read:1
          communications|notification|notification__notification_account_created_id_desc|false|false||{"_kind": "notification"}||payload.accountId:1,payload.createdOn:-1,_id.legacyId:-1
          communications|notification|notification__notification_account_read|false|false||{"_kind": "notification"}||payload.accountId:1,payload.read:1
          communications|notification_preference|notification_preference__accountId_asc|true|false||{"_kind": "notification_preference"}||payload.accountId:1
          communications|notification_delivery_guard|notification_delivery_guard__expiresAt_asc|false|false|0|{"_kind": "notification_delivery_guard"}||payload.expiresAt:1
          communications|notification_rate_limit|notification_rate_limit__expiresAt_asc|false|false|0|{"_kind": "notification_rate_limit"}||payload.expiresAt:1
          content|post|post__post_account_created_id_desc|false|false||{"_kind": "post"}||payload.accountId:1,payload.createdOn:-1,_id.legacyId:-1
          content|post|post__post_created_id_desc|false|false||{"_kind": "post"}||payload.createdOn:-1,_id.legacyId:-1
          content|post|post__post_root_created_asc|false|false||{"_kind": "post"}||payload.rootId:1,payload.createdOn:1
          content|post|post__post_parent|false|false||{"_kind": "post"}||payload.parentId:1
          content|post|post__post_expires|false|false||{"_kind": "post"}||payload.expiresOn:1
          content|post|post__post_account_parent|false|false||{"_kind": "post"}||payload.accountId:1,payload.parentId:1
          content|post_like|post_like__post_like_post_account_unique|true|false||{"_kind": "post_like"}||payload.postId:1,payload.accountId:1
          content|post_report|post_report__report_created_id_desc|false|false||{"_kind": "post_report"}||payload.createdOn:-1,_id.legacyId:-1
          content|post_report|post_report__report_status_created_id_desc|false|false||{"_kind": "post_report"}||payload.status:1,payload.createdOn:-1,_id.legacyId:-1
          content|post_report|post_report__openDedupeKey_asc|true|false||{"$and": [{"_kind": "post_report"}, {"payload.openDedupeKey": {"$exists": true}}]}||payload.openDedupeKey:1
          content|post_report|post_report__reportType_asc|false|false||{"_kind": "post_report"}||payload.reportType:1
          content|post_report|post_report__targetType_asc|false|false||{"_kind": "post_report"}||payload.targetType:1
          content|hidden_post_thread|hidden_post_thread__account_root_unique|true|false||{"_kind": "hidden_post_thread"}||payload.accountId:1,payload.rootPostId:1
          content|hidden_post_thread|hidden_post_thread__accountId_asc|false|false||{"_kind": "hidden_post_thread"}||payload.accountId:1
          content|hidden_post_thread|hidden_post_thread__rootPostId_asc|false|false||{"_kind": "hidden_post_thread"}||payload.rootPostId:1
          music|music_track|music_track__path_asc|true|false||{"_kind": "music_track"}||payload.path:1
          music|music_track|music_track__artist_asc|false|false||{"_kind": "music_track"}||payload.artist:1
          music|music_track|music_track__album_asc|false|false||{"_kind": "music_track"}||payload.album:1
          music|music_track|music_track__genre_asc|false|false||{"_kind": "music_track"}||payload.genre:1
          music|music_playlist|music_playlist__normalizedName_asc|true|false||{"_kind": "music_playlist"}||payload.normalizedName:1
          music|music_metadata_edit|music_metadata_edit__trackId_asc|false|false||{"_kind": "music_metadata_edit"}||payload.trackId:1
          music|music_metadata_edit|music_metadata_edit__expiresAt_asc|false|false||{"_kind": "music_metadata_edit"}||payload.expiresAt:1
          music|music_radio_history|music_radio_history__stationSequence_asc|false|false||{"_kind": "music_radio_history"}||payload.stationSequence:1
          music|music_radio_history|music_radio_history__occurredAt_asc|false|false||{"_kind": "music_radio_history"}||payload.occurredAt:1
          music|music_access_attempt|music_access_attempt__expiresAt_asc|false|false|0|{"_kind": "music_access_attempt"}||payload.expiresAt:1
          whatsforlunch|restaurant|restaurant__restaurant_coordinate_bounds|false|false||{"_kind": "restaurant"}||payload.address.latitude:1,payload.address.longitude:1
          whatsforlunch|restaurant|restaurant__restaurant_inventory_location_name|false|false||{"_kind": "restaurant"}||payload.searchState:1,payload.searchCity:1,payload.dedupeKey:1,_id.legacyId:1
          whatsforlunch|restaurant|restaurant__restaurant_inventory_city_name|false|false||{"_kind": "restaurant"}||payload.searchCity:1,payload.dedupeKey:1,_id.legacyId:1
          whatsforlunch|restaurant|restaurant__restaurant_inventory_state_name|false|false||{"_kind": "restaurant"}||payload.searchState:1,payload.dedupeKey:1,_id.legacyId:1
          whatsforlunch|restaurant|restaurant__restaurant_dedupe_key_member|false|false||{"_kind": "restaurant"}||payload.dedupeKey:1,_id.legacyId:1
          whatsforlunch|restaurant|restaurant__normalizedName_asc|true|false||{"$and": [{"_kind": "restaurant"}, {"payload.normalizedName": {"$exists": true}}]}||payload.normalizedName:1
          whatsforlunch|vote|vote__restaurant_account_unique|true|false||{"_kind": "vote"}||payload.restaurantId:1,payload.accountId:1
          whatsforlunch|vote|vote__restaurantId_asc|false|false||{"_kind": "vote"}||payload.restaurantId:1
          whatsforlunch|favorite|favorite__restaurant_account_unique|true|false||{"_kind": "favorite"}||payload.restaurantId:1,payload.accountId:1
          whatsforlunch|favorite|favorite__restaurantId_asc|false|false||{"_kind": "favorite"}||payload.restaurantId:1
          whatsforlunch|favorite|favorite__accountId_asc|false|false||{"_kind": "favorite"}||payload.accountId:1
          whatsforlunch|session|session__wfl_session_participant_created|false|false||{"_kind": "session"}||payload.participantAccountIds:1,payload.createdOn:-1,_id.legacyId:1
          whatsforlunch|session|session__createdByAccountId_asc|false|false||{"_kind": "session"}||payload.createdByAccountId:1
          whatsforlunch|session|session__participantAccountIds_asc|false|false||{"_kind": "session"}||payload.participantAccountIds:1
          whatsforlunch|session|session__wfl_session_delete_ttl|false|false|0|{"_kind": "session"}||payload.deleteOn:1
          shared_folder|audit_event|audit_event__shared_audit_occurred_desc|false|false||{"_kind": "audit_event"}||payload.occurredAt:-1
          shared_folder|audit_event|audit_event__shared_audit_account_occurred_desc|false|false||{"_kind": "audit_event"}||payload.accountId:1,payload.occurredAt:-1
          shared_folder|audit_event|audit_event__shared_audit_action_occurred_desc|false|false||{"_kind": "audit_event"}||payload.action:1,payload.occurredAt:-1
          shared_folder|audit_event|audit_event__shared_audit_outcome_occurred_desc|false|false||{"_kind": "audit_event"}||payload.outcome:1,payload.occurredAt:-1
          shared_folder|audit_event|audit_event__shared_audit_path_occurred_desc|false|false||{"_kind": "audit_event"}||payload.relativePath:1,payload.occurredAt:-1
          shared_folder|audit_event|audit_event__accountId_asc|false|false||{"_kind": "audit_event"}||payload.accountId:1
          shared_folder|audit_event|audit_event__action_asc|false|false||{"_kind": "audit_event"}||payload.action:1
          shared_folder|audit_event|audit_event__occurredAt_asc|false|false||{"_kind": "audit_event"}||payload.occurredAt:1
          shared_folder|audit_event|audit_event__expiresAt_asc|false|false|0|{"_kind": "audit_event"}||payload.expiresAt:1
          shared_folder|media_job|media_job__media_lru|false|false||{"_kind": "media_job"}||payload.status:1,payload.lastAccessedAt:1,_id.legacyId:1
          shared_folder|media_job|media_job__media_cleanup_due|false|false||{"_kind": "media_job"}||payload.artifactsCleaned:1,payload.cleanupAfter:1,payload.status:1,_id.legacyId:1
          shared_folder|media_job|media_job__ownerId_asc|false|false||{"_kind": "media_job"}||payload.ownerId:1
          shared_folder|media_job|media_job__cacheKey_asc|false|false||{"_kind": "media_job"}||payload.cacheKey:1
          shared_folder|media_job|media_job__activeCacheKey_asc|true|false||{"$and": [{"_kind": "media_job"}, {"payload.activeCacheKey": {"$exists": true}}]}||payload.activeCacheKey:1
          shared_folder|media_job|media_job__status_asc|false|false||{"_kind": "media_job"}||payload.status:1
          shared_folder|media_job|media_job__updatedAt_asc|false|false||{"_kind": "media_job"}||payload.updatedAt:1
          shared_folder|media_job|media_job__shared_media_delete_ttl|false|false|0|{"_kind": "media_job"}||payload.deleteAt:1
          shared_folder|mutation_recovery|mutation_recovery__ownerId_asc|false|false||{"_kind": "mutation_recovery"}||payload.ownerId:1
          shared_folder|mutation_recovery|mutation_recovery__updatedAt_asc|false|false||{"_kind": "mutation_recovery"}||payload.updatedAt:1
          shared_folder|recycle_item|recycle_item__shared_recycle_state_deleted_desc|false|false||{"_kind": "recycle_item"}||payload.state:1,payload.deletedAt:-1,_id.legacyId:-1
          shared_folder|recycle_item|recycle_item__shared_recycle_state_recovery_due|false|false||{"_kind": "recycle_item"}||payload.state:1,payload.deletedAt:1,_id.legacyId:1,payload.retryAfter:1
          shared_folder|recycle_item|recycle_item__shared_recycle_state_expiry|false|false||{"_kind": "recycle_item"}||payload.state:1,payload.expiresAt:1,_id.legacyId:1,payload.retryAfter:1
          shared_folder|upload_session|upload_session__upload_owner_state|false|false||{"_kind": "upload_session"}||payload.ownerId:1,payload.state:1
          shared_folder|upload_session|upload_session__upload_maintenance_due|false|false||{"_kind": "upload_session"}||payload.state:1,payload.maintenanceRetryAt:1,payload.expiresAt:1,_id.legacyId:1
          shared_folder|upload_session|upload_session__ownerId_asc|false|false||{"_kind": "upload_session"}||payload.ownerId:1
          shared_folder|upload_session|upload_session__expiresAt_asc|false|false||{"_kind": "upload_session"}||payload.expiresAt:1
          shared_folder|upload_session|upload_session__shared_upload_delete_ttl|false|false|0|{"_kind": "upload_session"}||payload.deleteAt:1
          vehicles|vehicle|vehicle__vin_asc|true|false||{"_kind": "vehicle"}||payload.vin:1
          admin_activity|admin_activity|admin_activity__admin_activity_created_id_desc|false|false||{"_kind": "admin_activity"}||payload.createdOn:-1,_id.legacyId:-1
          admin_activity|admin_activity|admin_activity__admin_activity_action_created_id_desc|false|false||{"_kind": "admin_activity"}||payload.action:1,payload.createdOn:-1,_id.legacyId:-1
          admin_activity|admin_activity|admin_activity__admin_activity_target_created_id_desc|false|false||{"_kind": "admin_activity"}||payload.targetType:1,payload.createdOn:-1,_id.legacyId:-1
          admin_activity|admin_activity|admin_activity__admin_activity_actor_created_id_desc|false|false||{"_kind": "admin_activity"}||payload.actorUsername:1,payload.createdOn:-1,_id.legacyId:-1
          """);

  @Test
  void finalManifestRetainsAllKindIndexesAfterRuntimeDocumentMappingsAreRemoved() {
    var expectedIndexes = java.util.stream.Stream.concat(
        FROZEN_ANNOTATION_INDEXES.stream(), MANUAL_ONLY_INDEXES.stream()).toList();
    assertThat(FROZEN_ANNOTATION_INDEXES).hasSize(92);
    assertThat(MANUAL_ONLY_INDEXES).hasSize(20);
    var kindIndexes = DomainCollectionManifest.ALL_INDEXES.stream()
        .filter(index -> index.kind().isPresent())
        .toList();
    assertThat(kindIndexes).hasSize(112);
    assertThat(kindIndexes)
        .containsExactlyInAnyOrderElementsOf(expectedIndexes);
  }

  private static List<DomainCollectionManifest.IndexDefinition> parseFrozen(String snapshot) {
    return snapshot.lines().filter(line -> !line.isBlank()).map(line -> {
      var fields = line.strip().split("\\|", -1);
      assertThat(fields).as("frozen index fields").hasSize(9);
      var keys = java.util.Arrays.stream(fields[8].split(","))
          .map(key -> {
            var separator = key.lastIndexOf(':');
            return new DomainCollectionManifest.IndexKey(
                key.substring(0, separator), Integer.parseInt(key.substring(separator + 1)));
          })
          .toList();
      return new DomainCollectionManifest.IndexDefinition(
          fields[0], Optional.of(fields[1]), fields[2], keys,
          Boolean.parseBoolean(fields[3]), Boolean.parseBoolean(fields[4]),
          Document.parse(fields[6]),
          fields[5].isEmpty() ? Optional.empty() : Optional.of(Long.parseLong(fields[5])),
          fields[7].isEmpty() ? Optional.empty() : Optional.of(fields[7]));
    }).toList();
  }

  private static DomainCollectionManifest.IndexDefinition manual(
      String collection,
      String kind,
      String legacyName,
      boolean unique,
      Long expireAfterSeconds,
      DomainCollectionManifest.IndexKey... keys) {
    return new DomainCollectionManifest.IndexDefinition(
        collection,
        Optional.of(kind),
        canonicalIndexName(kind, legacyName, List.of(keys)),
        List.of(keys),
        unique,
        false,
        Map.of("_kind", kind),
        Optional.ofNullable(expireAfterSeconds),
        Optional.empty());
  }

  private static DomainCollectionManifest.IndexKey asc(String legacyPath) {
    return mappedKey(legacyPath, 1);
  }

  private static DomainCollectionManifest.IndexKey desc(String legacyPath) {
    return mappedKey(legacyPath, -1);
  }

  private static DomainCollectionManifest.IndexKey mappedKey(String legacyPath, int direction) {
    return new DomainCollectionManifest.IndexKey(
        legacyPath.equals("_id") ? "_id.legacyId" : "payload." + legacyPath,
        direction);
  }

  private static String canonicalIndexName(
      String kind, String legacyName, List<DomainCollectionManifest.IndexKey> keys) {
    var suffix = legacyName == null
        ? keys.stream().map(DomainCollectionManifestLegacyIndexTest::indexNameToken)
            .collect(Collectors.joining("__"))
        : legacyName;
    var canonical = kind + "__" + suffix;
    if (canonical.length() <= 120) {
      return canonical;
    }
    return canonical.substring(0, 102) + "__" + sha256(canonical).substring(0, 16);
  }

  private static String indexNameToken(DomainCollectionManifest.IndexKey key) {
    var path = key.path();
    if (path.startsWith("payload.")) {
      path = path.substring("payload.".length());
    } else if (path.equals("_id.legacyId")) {
      path = "_id";
    }
    return path.replaceAll("[^A-Za-z0-9_]+", "_")
        + (key.direction() == 1 ? "_asc" : "_desc");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }
}

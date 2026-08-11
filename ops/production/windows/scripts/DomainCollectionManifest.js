"use strict";

const DIGEST = "576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24";
const CANONICAL_MANIFEST = String.raw`collection|accounts
collection|admin_activity
collection|application_migrations
collection|application_runtime
collection|canes_box_tracker
collection|communications
collection|content
collection|federation
collection|location
collection|music
collection|sessions
collection|shared_folder
collection|vehicles
collection|whatsforlunch
kind|accounts|account|accounts|dev.christopherbell.account.model.Account|1
kind|accounts|account_follow|account_follows|dev.christopherbell.account.follow.AccountFollow|1
kind|accounts|account_trust_relationship|account_trust_relationships|dev.christopherbell.account.trust.AccountTrustRelationship|1
kind|accounts|account_deletion_job|account_deletion_jobs|dev.christopherbell.account.deletion.AccountDeletionJob|1
kind|sessions|browser_session|browser_sessions|dev.christopherbell.configuration.security.browser.BrowserSession|1
kind|sessions|conversation_archive_state|conversation_archive_states|dev.christopherbell.message.conversation.ConversationArchiveState|1
kind|communications|message|messages|dev.christopherbell.message.model.Message|1
kind|communications|notification|notifications|dev.christopherbell.notification.model.Notification|1
kind|communications|notification_preference|notification_preferences|dev.christopherbell.notification.preference.NotificationPreference|1
kind|communications|notification_delivery_guard|notification_delivery_guards|dev.christopherbell.notification.delivery.NotificationDeliveryGuard|1
kind|communications|notification_rate_limit|notification_rate_limits|dev.christopherbell.notification.delivery.NotificationRateLimit|1
kind|content|post|posts|dev.christopherbell.post.model.Post|1
kind|content|post_like|post_likes|dev.christopherbell.post.like.PostLike|1
kind|content|post_report|post_reports|dev.christopherbell.report.model.PostReport|1
kind|content|hidden_post_thread|hidden_post_threads|dev.christopherbell.post.hide.HiddenPostThread|1
kind|content|post_link_preview_cache|post_link_preview_cache|dev.christopherbell.post.preview.PostLinkPreviewCacheEntry|1
kind|federation|federation_scan_state|federation_scan_state|dev.christopherbell.federation.outbound.FederationScanState|1
kind|federation|federation_delivery_job|federation_delivery_jobs|dev.christopherbell.federation.outbound.FederationDeliveryJob|1
kind|music|music_track|music_tracks|dev.christopherbell.music.catalog.MusicTrack|1
kind|music|music_playlist|music_playlists|dev.christopherbell.music.library.MusicPlaylist|1
kind|music|music_metadata_edit|music_metadata_edits|dev.christopherbell.music.metadata.MusicMetadataEdit|1
kind|music|music_runtime_state|music_runtime_state|dev.christopherbell.music.radio.MusicRuntimeStateDocument|1
kind|music|music_radio_history|music_radio_history|dev.christopherbell.music.radio.MusicRadioHistoryEvent|1
kind|music|music_access_attempt|music_access_attempts|dev.christopherbell.music.security.MusicAccessAttempt|1
kind|whatsforlunch|restaurant|whatsforlunch|dev.christopherbell.whatsforlunch.restaurant.model.Restaurant|1
kind|whatsforlunch|vote|whatsforlunch_ratings|dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote|1
kind|whatsforlunch|favorite|whatsforlunch_favorites|dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite|1
kind|whatsforlunch|preference|whatsforlunch_preferences|dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference|1
kind|whatsforlunch|session|whatsforlunch_sessions|dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession|1
kind|whatsforlunch|daily_picks|whatsforlunch_daily_picks|dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks|1
kind|whatsforlunch|import_state|restaurant_import_state|dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState|1
kind|whatsforlunch|import_preview|restaurant_import_previews|dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewDocument|1
kind|shared_folder|audit_event|shared_folder_audit|dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent|1
kind|shared_folder|maintenance_lease|shared_folder_maintenance_leases|dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseDocument|1
kind|shared_folder|media_job|shared_folder_media_jobs|dev.christopherbell.sharedfolder.media.MediaJob|1
kind|shared_folder|mutation_recovery|shared_folder_mutation_recoveries|dev.christopherbell.sharedfolder.service.SharedFolderMutationRecovery|1
kind|shared_folder|radio_state|shared_folder_radio|dev.christopherbell.sharedfolder.radio.SharedFolderRadioDocument|1
kind|shared_folder|recycle_item|shared_folder_recycle_items|dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleItem|1
kind|shared_folder|upload_session|shared_folder_upload_sessions|dev.christopherbell.sharedfolder.upload.SharedFolderUploadSession|1
kind|vehicles|vehicle|vehicles|dev.christopherbell.vehicle.model.Vehicle|1
kind|vehicles|vin_decode_cache|vehicle_vin_decode_cache|dev.christopherbell.vehicle.model.VehicleVinDecodeCache|1
kind|vehicles|nhtsa_import_state|vehicle_import_state|dev.christopherbell.vehicle.nhtsa.model.NhtsaVinImportState|1
kind|vehicles|random_vin_import_state|vehicle_import_state|dev.christopherbell.vehicle.randomvin.model.RandomVinImportState|1
kind|location|zip_coordinate|location_zip_coordinates|dev.christopherbell.location.model.ZipCoordinate|1
kind|location|zip_import_state|zip_coordinate_import_state|dev.christopherbell.location.model.ZipCoordinateImportState|1
kind|canes_box_tracker|price_snapshot|canes_box_price_snapshots|dev.christopherbell.canesboxtracker.model.CanesBoxPriceSnapshot|1
kind|application_runtime|application_lease|application_leases|dev.christopherbell.libs.mongo.lease.MongoLeaseDocument|1
kind|application_runtime|scheduled_collector_run|scheduled_collector_runs|dev.christopherbell.libs.mongo.lease.ScheduledCollectorRun|1
kind|application_migrations|migration_record|application_migrations|dev.christopherbell.configuration.mongo.migration.MigrationRecord|1
kind|application_migrations|domain_collection_cutover|<none>|dev.christopherbell.configuration.mongo.migration.DomainCollectionCutoverLedger|1
kind|admin_activity|admin_activity|admin_activity|dev.christopherbell.admin.model.AdminActivity|1
kind|admin_activity|pending_action|command_center_pending_actions|dev.christopherbell.admin.commandcenter.action.PendingActionDocument|1
index|accounts|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|accounts|account|account__email_asc|payload.email:1,|true|false|{_kind=account}|<none>|<none>
index|accounts|account|account__federation_actor_lookup|payload.status:1,payload.federationEnabled:1,payload.username:1,|false|false|{_kind=account}|<none>|<none>
index|accounts|account|account__passwordResetTokenHash_asc|payload.passwordResetTokenHash:1,|false|false|{_kind=account}|<none>|<none>
index|accounts|account|account__username_asc|payload.username:1,|true|false|{_kind=account}|<none>|<none>
index|accounts|account_follow|account_follow__account_follow_follower_target_unique|payload.followerAccountId:1,payload.followedAccountId:1,|true|false|{_kind=account_follow}|<none>|<none>
index|accounts|account_follow|account_follow__account_follow_target|payload.followedAccountId:1,|false|false|{_kind=account_follow}|<none>|<none>
index|accounts|account_trust_relationship|account_trust_relationship__ownerAccountId_asc|payload.ownerAccountId:1,|false|false|{_kind=account_trust_relationship}|<none>|<none>
index|accounts|account_trust_relationship|account_trust_relationship__owner_target_type_unique|payload.ownerAccountId:1,payload.targetAccountId:1,payload.type:1,|true|false|{_kind=account_trust_relationship}|<none>|<none>
index|accounts|account_trust_relationship|account_trust_relationship__targetAccountId_asc|payload.targetAccountId:1,|false|false|{_kind=account_trust_relationship}|<none>|<none>
index|accounts|account_trust_relationship|account_trust_relationship__void_people_incoming_block|payload.targetAccountId:1,payload.type:1,payload.ownerAccountId:1,|false|false|{_kind=account_trust_relationship}|<none>|<none>
index|admin_activity|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|admin_activity|admin_activity|admin_activity__admin_activity_action_created_id_desc|payload.action:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=admin_activity}|<none>|<none>
index|admin_activity|admin_activity|admin_activity__admin_activity_actor_created_id_desc|payload.actorUsername:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=admin_activity}|<none>|<none>
index|admin_activity|admin_activity|admin_activity__admin_activity_created_id_desc|payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=admin_activity}|<none>|<none>
index|admin_activity|admin_activity|admin_activity__admin_activity_target_created_id_desc|payload.targetType:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=admin_activity}|<none>|<none>
index|application_migrations|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|application_migrations|migration_record|migration_record__migration_status_completed|payload.status:1,payload.completedAt:-1,|false|false|{_kind=migration_record}|<none>|<none>
index|application_runtime|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|application_runtime|application_lease|application_lease__lease_expiry|payload.expiresAt:1,|false|false|{_kind=application_lease}|<none>|<none>
index|application_runtime|scheduled_collector_run|scheduled_collector_run__scheduled_collector_status_completed|payload.status:1,payload.completedOn:-1,|false|false|{_kind=scheduled_collector_run}|<none>|<none>
index|canes_box_tracker|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|communications|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|communications|message|message__message_conversation_created_asc|payload.conversationKey:1,payload.createdOn:1,|false|false|{_kind=message}|<none>|<none>
index|communications|message|message__message_conversation_created_id_desc|payload.conversationKey:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=message}|<none>|<none>
index|communications|message|message__message_participant_created_desc|payload.participantIds:1,payload.createdOn:-1,|false|false|{_kind=message}|<none>|<none>
index|communications|message|message__message_participant_created_id_desc|payload.participantIds:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=message}|<none>|<none>
index|communications|message|message__message_recipient_sender_read|payload.recipientAccountId:1,payload.senderAccountId:1,payload.read:1,|false|false|{_kind=message}|<none>|<none>
index|communications|notification|notification__notification_account_created_id_desc|payload.accountId:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=notification}|<none>|<none>
index|communications|notification|notification__notification_account_read|payload.accountId:1,payload.read:1,|false|false|{_kind=notification}|<none>|<none>
index|communications|notification_delivery_guard|notification_delivery_guard__expiresAt_asc|payload.expiresAt:1,|false|false|{_kind=notification_delivery_guard}|0|<none>
index|communications|notification_preference|notification_preference__accountId_asc|payload.accountId:1,|true|false|{_kind=notification_preference}|<none>|<none>
index|communications|notification_rate_limit|notification_rate_limit__expiresAt_asc|payload.expiresAt:1,|false|false|{_kind=notification_rate_limit}|0|<none>
index|content|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|content|hidden_post_thread|hidden_post_thread__accountId_asc|payload.accountId:1,|false|false|{_kind=hidden_post_thread}|<none>|<none>
index|content|hidden_post_thread|hidden_post_thread__account_root_unique|payload.accountId:1,payload.rootPostId:1,|true|false|{_kind=hidden_post_thread}|<none>|<none>
index|content|hidden_post_thread|hidden_post_thread__rootPostId_asc|payload.rootPostId:1,|false|false|{_kind=hidden_post_thread}|<none>|<none>
index|content|post|post__federation_outbound_post_scan|payload.federationOutboundEligible:1,payload.createdOn:1,_id.legacyId:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__post_account_created_id_desc|payload.accountId:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__post_account_parent|payload.accountId:1,payload.parentId:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__post_created_id_desc|payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__post_expires|payload.expiresOn:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__post_parent|payload.parentId:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__post_root_created_asc|payload.rootId:1,payload.createdOn:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__void_discovery_fading|payload.parentId:1,payload.expiresOn:1,_id.legacyId:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__void_discovery_new|payload.parentId:1,payload.createdOn:-1,_id.legacyId:-1,payload.expiresOn:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__void_discovery_revived|payload.parentId:1,payload.lastExtendedOn:-1,_id.legacyId:-1,payload.expiresOn:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__void_discovery_topic|payload.topics.canonical:1,payload.expiresOn:1,payload.rootId:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__void_people_active_pool|payload.expiresOn:1,payload.accountId:1,|false|false|{_kind=post}|<none>|<none>
index|content|post|post__void_people_authored_activity|payload.accountId:1,payload.expiresOn:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=post}|<none>|<none>
index|content|post_like|post_like__post_like_post_account_unique|payload.postId:1,payload.accountId:1,|true|false|{_kind=post_like}|<none>|<none>
index|content|post_link_preview_cache|post_link_preview_cache__post_link_preview_cache_expiry|payload.expiresOn:1,|false|false|{_kind=post_link_preview_cache}|0|<none>
index|content|post_report|post_report__openDedupeKey_asc|payload.openDedupeKey:1,|true|false|{$and=[{_kind=post_report}, {payload.openDedupeKey={$exists=true}}]}|<none>|<none>
index|content|post_report|post_report__reportType_asc|payload.reportType:1,|false|false|{_kind=post_report}|<none>|<none>
index|content|post_report|post_report__report_created_id_desc|payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=post_report}|<none>|<none>
index|content|post_report|post_report__report_status_created_id_desc|payload.status:1,payload.createdOn:-1,_id.legacyId:-1,|false|false|{_kind=post_report}|<none>|<none>
index|content|post_report|post_report__targetType_asc|payload.targetType:1,|false|false|{_kind=post_report}|<none>|<none>
index|federation|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|federation|federation_delivery_job|federation_delivery_job__federation_delivery_due|payload.state:1,payload.nextAttemptOn:1,payload.createdOn:1,|false|false|{_kind=federation_delivery_job}|<none>|<none>
index|federation|federation_delivery_job|federation_delivery_job__federation_delivery_expired_claim|payload.state:1,payload.claimUntil:1,|false|false|{_kind=federation_delivery_job}|<none>|<none>
index|federation|federation_delivery_job|federation_delivery_job__federation_delivery_post_peer_unique|payload.postId:1,payload.peerName:1,|true|false|{_kind=federation_delivery_job}|<none>|<none>
index|location|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|music|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|music|music_access_attempt|music_access_attempt__expiresAt_asc|payload.expiresAt:1,|false|false|{_kind=music_access_attempt}|0|<none>
index|music|music_metadata_edit|music_metadata_edit__expiresAt_asc|payload.expiresAt:1,|false|false|{_kind=music_metadata_edit}|<none>|<none>
index|music|music_metadata_edit|music_metadata_edit__trackId_asc|payload.trackId:1,|false|false|{_kind=music_metadata_edit}|<none>|<none>
index|music|music_playlist|music_playlist__normalizedName_asc|payload.normalizedName:1,|true|false|{_kind=music_playlist}|<none>|<none>
index|music|music_radio_history|music_radio_history__occurredAt_asc|payload.occurredAt:1,|false|false|{_kind=music_radio_history}|<none>|<none>
index|music|music_radio_history|music_radio_history__stationSequence_asc|payload.stationSequence:1,|false|false|{_kind=music_radio_history}|<none>|<none>
index|music|music_track|music_track__album_asc|payload.album:1,|false|false|{_kind=music_track}|<none>|<none>
index|music|music_track|music_track__artist_asc|payload.artist:1,|false|false|{_kind=music_track}|<none>|<none>
index|music|music_track|music_track__genre_asc|payload.genre:1,|false|false|{_kind=music_track}|<none>|<none>
index|music|music_track|music_track__path_asc|payload.path:1,|true|false|{_kind=music_track}|<none>|<none>
index|sessions|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|sessions|browser_session|browser_session__absoluteExpiresOn_asc|payload.absoluteExpiresOn:1,|false|false|{_kind=browser_session}|0|<none>
index|sessions|browser_session|browser_session__accountId_asc|payload.accountId:1,|false|false|{_kind=browser_session}|<none>|<none>
index|sessions|conversation_archive_state|conversation_archive_state__conversation_archive_owner_key_unique|payload.ownerAccountId:1,payload.conversationKey:1,|true|false|{_kind=conversation_archive_state}|<none>|<none>
index|shared_folder|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|shared_folder|audit_event|audit_event__accountId_asc|payload.accountId:1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|audit_event|audit_event__action_asc|payload.action:1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|audit_event|audit_event__expiresAt_asc|payload.expiresAt:1,|false|false|{_kind=audit_event}|0|<none>
index|shared_folder|audit_event|audit_event__occurredAt_asc|payload.occurredAt:1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|audit_event|audit_event__shared_audit_account_occurred_desc|payload.accountId:1,payload.occurredAt:-1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|audit_event|audit_event__shared_audit_action_occurred_desc|payload.action:1,payload.occurredAt:-1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|audit_event|audit_event__shared_audit_occurred_desc|payload.occurredAt:-1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|audit_event|audit_event__shared_audit_outcome_occurred_desc|payload.outcome:1,payload.occurredAt:-1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|audit_event|audit_event__shared_audit_path_occurred_desc|payload.relativePath:1,payload.occurredAt:-1,|false|false|{_kind=audit_event}|<none>|<none>
index|shared_folder|media_job|media_job__activeCacheKey_asc|payload.activeCacheKey:1,|true|false|{$and=[{_kind=media_job}, {payload.activeCacheKey={$exists=true}}]}|<none>|<none>
index|shared_folder|media_job|media_job__cacheKey_asc|payload.cacheKey:1,|false|false|{_kind=media_job}|<none>|<none>
index|shared_folder|media_job|media_job__media_cleanup_due|payload.artifactsCleaned:1,payload.cleanupAfter:1,payload.status:1,_id.legacyId:1,|false|false|{_kind=media_job}|<none>|<none>
index|shared_folder|media_job|media_job__media_lru|payload.status:1,payload.lastAccessedAt:1,_id.legacyId:1,|false|false|{_kind=media_job}|<none>|<none>
index|shared_folder|media_job|media_job__ownerId_asc|payload.ownerId:1,|false|false|{_kind=media_job}|<none>|<none>
index|shared_folder|media_job|media_job__shared_media_delete_ttl|payload.deleteAt:1,|false|false|{_kind=media_job}|0|<none>
index|shared_folder|media_job|media_job__status_asc|payload.status:1,|false|false|{_kind=media_job}|<none>|<none>
index|shared_folder|media_job|media_job__updatedAt_asc|payload.updatedAt:1,|false|false|{_kind=media_job}|<none>|<none>
index|shared_folder|mutation_recovery|mutation_recovery__ownerId_asc|payload.ownerId:1,|false|false|{_kind=mutation_recovery}|<none>|<none>
index|shared_folder|mutation_recovery|mutation_recovery__updatedAt_asc|payload.updatedAt:1,|false|false|{_kind=mutation_recovery}|<none>|<none>
index|shared_folder|recycle_item|recycle_item__shared_recycle_state_deleted_desc|payload.state:1,payload.deletedAt:-1,_id.legacyId:-1,|false|false|{_kind=recycle_item}|<none>|<none>
index|shared_folder|recycle_item|recycle_item__shared_recycle_state_expiry|payload.state:1,payload.expiresAt:1,_id.legacyId:1,payload.retryAfter:1,|false|false|{_kind=recycle_item}|<none>|<none>
index|shared_folder|recycle_item|recycle_item__shared_recycle_state_recovery_due|payload.state:1,payload.deletedAt:1,_id.legacyId:1,payload.retryAfter:1,|false|false|{_kind=recycle_item}|<none>|<none>
index|shared_folder|upload_session|upload_session__expiresAt_asc|payload.expiresAt:1,|false|false|{_kind=upload_session}|<none>|<none>
index|shared_folder|upload_session|upload_session__ownerId_asc|payload.ownerId:1,|false|false|{_kind=upload_session}|<none>|<none>
index|shared_folder|upload_session|upload_session__shared_upload_delete_ttl|payload.deleteAt:1,|false|false|{_kind=upload_session}|0|<none>
index|shared_folder|upload_session|upload_session__upload_maintenance_due|payload.state:1,payload.maintenanceRetryAt:1,payload.expiresAt:1,_id.legacyId:1,|false|false|{_kind=upload_session}|<none>|<none>
index|shared_folder|upload_session|upload_session__upload_owner_state|payload.ownerId:1,payload.state:1,|false|false|{_kind=upload_session}|<none>|<none>
index|vehicles|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|vehicles|vehicle|vehicle__vin_asc|payload.vin:1,|true|false|{_kind=vehicle}|<none>|<none>
index|vehicles|vin_decode_cache|vin_decode_cache__vehicle_vin_cache_expiry|payload.expiresOn:1,|false|false|{_kind=vin_decode_cache}|0|<none>
index|whatsforlunch|<global>|_id_|_id:1,|true|false|{}|<none>|<none>
index|whatsforlunch|favorite|favorite__accountId_asc|payload.accountId:1,|false|false|{_kind=favorite}|<none>|<none>
index|whatsforlunch|favorite|favorite__restaurantId_asc|payload.restaurantId:1,|false|false|{_kind=favorite}|<none>|<none>
index|whatsforlunch|favorite|favorite__restaurant_account_unique|payload.restaurantId:1,payload.accountId:1,|true|false|{_kind=favorite}|<none>|<none>
index|whatsforlunch|import_preview|import_preview__restaurant_import_preview_actor_created|payload.actorAccountId:1,payload.createdOn:-1,|false|false|{_kind=import_preview}|<none>|<none>
index|whatsforlunch|import_preview|import_preview__restaurant_import_preview_expiry|payload.expiresOn:1,|false|false|{_kind=import_preview}|0|<none>
index|whatsforlunch|restaurant|restaurant__normalizedName_asc|payload.normalizedName:1,|true|false|{$and=[{_kind=restaurant}, {payload.normalizedName={$exists=true}}]}|<none>|<none>
index|whatsforlunch|restaurant|restaurant__restaurant_coordinate_bounds|payload.address.latitude:1,payload.address.longitude:1,|false|false|{_kind=restaurant}|<none>|<none>
index|whatsforlunch|restaurant|restaurant__restaurant_dedupe_key_member|payload.dedupeKey:1,_id.legacyId:1,|false|false|{_kind=restaurant}|<none>|<none>
index|whatsforlunch|restaurant|restaurant__restaurant_inventory_city_name|payload.searchCity:1,payload.dedupeKey:1,_id.legacyId:1,|false|false|{_kind=restaurant}|<none>|<none>
index|whatsforlunch|restaurant|restaurant__restaurant_inventory_location_name|payload.searchState:1,payload.searchCity:1,payload.dedupeKey:1,_id.legacyId:1,|false|false|{_kind=restaurant}|<none>|<none>
index|whatsforlunch|restaurant|restaurant__restaurant_inventory_state_name|payload.searchState:1,payload.dedupeKey:1,_id.legacyId:1,|false|false|{_kind=restaurant}|<none>|<none>
index|whatsforlunch|session|session__createdByAccountId_asc|payload.createdByAccountId:1,|false|false|{_kind=session}|<none>|<none>
index|whatsforlunch|session|session__participantAccountIds_asc|payload.participantAccountIds:1,|false|false|{_kind=session}|<none>|<none>
index|whatsforlunch|session|session__wfl_session_delete_ttl|payload.deleteOn:1,|false|false|{_kind=session}|0|<none>
index|whatsforlunch|session|session__wfl_session_participant_created|payload.participantAccountIds:1,payload.createdOn:-1,_id.legacyId:1,|false|false|{_kind=session}|<none>|<none>
index|whatsforlunch|vote|vote__restaurantId_asc|payload.restaurantId:1,|false|false|{_kind=vote}|<none>|<none>
index|whatsforlunch|vote|vote__restaurant_account_unique|payload.restaurantId:1,payload.accountId:1,|true|false|{_kind=vote}|<none>|<none>
`;

function fail(message) {
  throw new Error(message);
}

function parsePartial(value) {
  if (value === "{}") return {};
  const simple = value.match(/^\{_kind=([a-z0-9_]+)\}$/);
  if (simple) return { _kind: simple[1] };
  const sparse = value.match(/^\{\$and=\[\{_kind=([a-z0-9_]+)\}, \{([^=]+)=\{\$exists=true\}\}\]\}$/);
  if (sparse) {
    return { $and: [{ _kind: sparse[1] }, { [sparse[2]]: { $exists: true } }] };
  }
  fail("Mongo manifest partial filter is invalid.");
}

function parseCanonicalManifest(text) {
  const targets = [];
  const kinds = [];
  const indexes = [];
  for (const line of text.split("\n").filter(Boolean)) {
    const fields = line.split("|");
    if (fields[0] === "collection" && fields.length === 2) {
      targets.push(fields[1]);
    } else if (fields[0] === "kind" && fields.length === 6) {
      kinds.push({
        target: fields[1],
        kind: fields[2],
        source: fields[3] === "<none>" ? null : fields[3],
        ownerType: fields[4],
        schemaVersion: Number(fields[5]),
        sourceId: fields[2] === "nhtsa_import_state" ? "nhtsa"
          : fields[2] === "random_vin_import_state" ? "randomvin" : null
      });
    } else if (fields[0] === "index" && fields.length === 10) {
      const keys = fields[4].split(",").filter(Boolean).map((key) => {
        const separator = key.lastIndexOf(":");
        return [key.slice(0, separator), Number(key.slice(separator + 1))];
      });
      indexes.push({
        target: fields[1],
        kind: fields[2] === "<global>" ? null : fields[2],
        name: fields[3],
        keys,
        unique: fields[5] === "true",
        sparse: fields[6] === "true",
        partialFilterExpression: parsePartial(fields[7]),
        expireAfterSeconds: fields[8] === "<none>" ? null : Number(fields[8]),
        collation: fields[9] === "<none>" ? null : fields[9]
      });
    } else {
      fail("Mongo canonical manifest line is invalid.");
    }
  }
  if (targets.length !== 14 || kinds.length !== 52 || indexes.length !== 126) {
    fail("Mongo manifest cardinality is invalid.");
  }
  return deepFreeze({
    digest: DIGEST,
    targets,
    kinds,
    indexes,
    dropOnly: ["music_queue_state", "music_radio_state"]
  });
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.values(value).forEach(deepFreeze);
    Object.freeze(value);
  }
  return value;
}

const MANIFEST = parseCanonicalManifest(CANONICAL_MANIFEST);

function requireDigest(value) {
  if (String(value) !== DIGEST) fail("Mongo manifest digest is invalid.");
  return MANIFEST;
}

const exported = Object.freeze({
  DIGEST,
  CANONICAL_MANIFEST,
  MANIFEST,
  requireDigest
});

if (typeof module !== "undefined" && module.exports) {
  module.exports = exported;
}
if (typeof globalThis !== "undefined") {
  globalThis.DomainCollectionManifest = exported;
}

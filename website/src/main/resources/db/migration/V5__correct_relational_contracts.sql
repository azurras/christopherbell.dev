ALTER TABLE ${schema_prefix}mobility.vin_decode_cache
  ALTER COLUMN body TYPE text USING convert_from(body, 'UTF8'),
  ALTER COLUMN body DROP NOT NULL,
  ALTER COLUMN created_on DROP NOT NULL,
  ALTER COLUMN expires_on DROP NOT NULL,
  ALTER COLUMN last_updated_on DROP NOT NULL;

DO $$
BEGIN
  IF EXISTS (
      SELECT 1
      FROM ${schema_prefix}lunch.lunch_preference
      WHERE radius_miles IS NOT NULL AND radius_miles <> trunc(radius_miles)) THEN
    RAISE EXCEPTION 'lunch preference radius contains a fractional source value';
  END IF;
END
$$;

ALTER TABLE ${schema_prefix}lunch.lunch_preference
  ALTER COLUMN radius_miles TYPE integer USING radius_miles::integer,
  ALTER COLUMN radius_miles DROP NOT NULL;

CREATE UNIQUE INDEX restaurant__normalized_name_present_uk
  ON ${schema_prefix}lunch.restaurant (normalized_name)
  WHERE normalized_name IS NOT NULL;

ALTER TABLE ${schema_prefix}music.playlist
  DROP CONSTRAINT playlist_updated_by_account_fk,
  ADD CONSTRAINT playlist_updated_by_account_fk FOREIGN KEY (updated_by_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT;

CREATE INDEX admin_activity__admin_activity_created_id_desc
  ON ${schema_prefix}platform.admin_activity (created_on DESC, admin_activity_id DESC);
CREATE INDEX admin_activity__admin_activity_action_created_id_desc
  ON ${schema_prefix}platform.admin_activity
    (action, created_on DESC, admin_activity_id DESC);
CREATE INDEX admin_activity__admin_activity_target_created_id_desc
  ON ${schema_prefix}platform.admin_activity
    (target_type, created_on DESC, admin_activity_id DESC);
CREATE INDEX admin_activity__admin_activity_actor_created_id_desc
  ON ${schema_prefix}platform.admin_activity
    (actor_username, created_on DESC, admin_activity_id DESC);

CREATE UNIQUE INDEX account__email_asc
  ON ${schema_prefix}identity.account (email);

CREATE INDEX message__participant_created_parent
  ON ${schema_prefix}communication.message (created_on DESC, message_id DESC);

CREATE INDEX post__void_discovery_topic_parent
  ON ${schema_prefix}social.post (expires_on, root_post_id);

CREATE INDEX music_track__artist_asc
  ON ${schema_prefix}music.track (artist);
CREATE INDEX music_track__album_asc
  ON ${schema_prefix}music.track (album);
CREATE INDEX music_track__genre_asc
  ON ${schema_prefix}music.track (genre);
CREATE INDEX music_radio_history__occurred_at_asc
  ON ${schema_prefix}music.radio_history (occurred_at);

DROP INDEX ${schema_prefix}lunch.restaurant__restaurant_inventory_location_name;
CREATE INDEX restaurant__restaurant_coordinate_bounds
  ON ${schema_prefix}lunch.restaurant (latitude, longitude);
CREATE INDEX restaurant__restaurant_inventory_location_name
  ON ${schema_prefix}lunch.restaurant
    (search_state, search_city, dedupe_key, restaurant_id);
CREATE INDEX restaurant__restaurant_inventory_city_name
  ON ${schema_prefix}lunch.restaurant (search_city, dedupe_key, restaurant_id);
CREATE INDEX restaurant__restaurant_inventory_state_name
  ON ${schema_prefix}lunch.restaurant (search_state, dedupe_key, restaurant_id);
CREATE INDEX restaurant__restaurant_dedupe_key_member
  ON ${schema_prefix}lunch.restaurant (dedupe_key, restaurant_id);
CREATE UNIQUE INDEX vote__restaurant_account_unique
  ON ${schema_prefix}lunch.restaurant_vote (restaurant_id, account_id);
CREATE INDEX vote__restaurant_id_asc
  ON ${schema_prefix}lunch.restaurant_vote (restaurant_id);
CREATE UNIQUE INDEX favorite__restaurant_account_unique
  ON ${schema_prefix}lunch.restaurant_favorite (restaurant_id, account_id);
CREATE INDEX favorite__restaurant_id_asc
  ON ${schema_prefix}lunch.restaurant_favorite (restaurant_id);
CREATE INDEX session__participant_account_created
  ON ${schema_prefix}lunch.lunch_session_participant (account_id);
CREATE INDEX session__participant_created_parent
  ON ${schema_prefix}lunch.lunch_session (created_on DESC, lunch_session_id);
CREATE INDEX session__created_by_account
  ON ${schema_prefix}lunch.lunch_session (created_by_account_id);
CREATE INDEX restaurant_import_preview__actor_created
  ON ${schema_prefix}lunch.restaurant_import_preview (actor_account_id, created_on DESC);

CREATE INDEX audit_event__account_occurred_desc
  ON ${schema_prefix}shared_folder.audit_event (account_id, occurred_at DESC);
CREATE INDEX audit_event__action_occurred_desc
  ON ${schema_prefix}shared_folder.audit_event (action, occurred_at DESC);
CREATE INDEX audit_event__outcome_occurred_desc
  ON ${schema_prefix}shared_folder.audit_event (outcome, occurred_at DESC);
CREATE INDEX audit_event__path_occurred_desc
  ON ${schema_prefix}shared_folder.audit_event (relative_path, occurred_at DESC);
CREATE INDEX audit_event__account_id_asc
  ON ${schema_prefix}shared_folder.audit_event (account_id);
CREATE INDEX audit_event__action_asc
  ON ${schema_prefix}shared_folder.audit_event (action);
CREATE INDEX audit_event__occurred_at_asc
  ON ${schema_prefix}shared_folder.audit_event (occurred_at);

ALTER TABLE ${schema_prefix}shared_folder.media_job
  DROP CONSTRAINT media_job_active_cache_key_uk;
CREATE UNIQUE INDEX media_job__active_cache_key_present_uk
  ON ${schema_prefix}shared_folder.media_job (active_cache_key)
  WHERE active_cache_key IS NOT NULL;
DROP INDEX ${schema_prefix}shared_folder.media_job__media_cleanup_due;
CREATE INDEX media_job__media_lru
  ON ${schema_prefix}shared_folder.media_job
    (status, last_accessed_at, media_job_id);
CREATE INDEX media_job__media_cleanup_due
  ON ${schema_prefix}shared_folder.media_job
    (artifacts_cleaned, cleanup_after, status, media_job_id);
CREATE INDEX media_job__owner_id_asc
  ON ${schema_prefix}shared_folder.media_job (owner_id);
CREATE INDEX media_job__cache_key_asc
  ON ${schema_prefix}shared_folder.media_job (cache_key);
CREATE INDEX media_job__status_asc
  ON ${schema_prefix}shared_folder.media_job (status);
CREATE INDEX media_job__updated_at_asc
  ON ${schema_prefix}shared_folder.media_job (updated_at);

CREATE INDEX mutation_recovery__owner_id_asc
  ON ${schema_prefix}shared_folder.mutation_recovery (owner_id);
CREATE INDEX mutation_recovery__updated_at_asc
  ON ${schema_prefix}shared_folder.mutation_recovery (updated_at);
CREATE INDEX recycle_item__state_deleted_desc
  ON ${schema_prefix}shared_folder.recycle_item
    (state, deleted_at DESC, recycle_item_id DESC);
CREATE INDEX recycle_item__state_recovery_due
  ON ${schema_prefix}shared_folder.recycle_item
    (state, deleted_at, recycle_item_id, retry_after);
CREATE INDEX recycle_item__state_expiry
  ON ${schema_prefix}shared_folder.recycle_item
    (state, expires_at, recycle_item_id, retry_after);
DROP INDEX ${schema_prefix}shared_folder.upload_session__upload_maintenance_due;
CREATE INDEX upload_session__upload_maintenance_due
  ON ${schema_prefix}shared_folder.upload_session
    (state, maintenance_retry_at, expires_at, upload_session_id);
CREATE INDEX upload_session__expires_at_asc
  ON ${schema_prefix}shared_folder.upload_session (expires_at);
CREATE INDEX upload_session__delete_at_asc
  ON ${schema_prefix}shared_folder.upload_session (delete_at);

DROP INDEX ${schema_prefix}platform.scheduled_collector_run__scheduled_collector_status_completed;
CREATE INDEX scheduled_collector_run__scheduled_collector_status_completed
  ON ${schema_prefix}platform.scheduled_collector_run (status, completed_on DESC);

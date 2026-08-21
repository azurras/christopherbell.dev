CREATE TABLE ${schema_prefix}mobility.vehicle (
  vehicle_id varchar(128) PRIMARY KEY,
  body_style text,
  body_class text,
  color text,
  created_by varchar(128),
  created_on timestamptz,
  drivetrain text,
  doors integer,
  engine text,
  fuel_type text,
  gvwr text,
  last_modified_by varchar(128),
  last_updated_on timestamptz,
  license_plate text,
  license_plate_state varchar(16),
  make text,
  manufacturer text,
  manufacturer_id text,
  mileage bigint,
  model text,
  model_year integer,
  nhtsa_error_code text,
  nhtsa_error_text text,
  nhtsa_last_decoded_on timestamptz,
  nickname text,
  notes text,
  plant_city text,
  plant_country text,
  plant_state text,
  purchase_date date,
  series text,
  transmission text,
  trim text,
  vehicle_type text,
  vin varchar(17) NOT NULL,
  CONSTRAINT vehicle_vin_uk UNIQUE (vin),
  CONSTRAINT vehicle_mileage_nonnegative_ck CHECK (mileage IS NULL OR mileage >= 0),
  CONSTRAINT vehicle_model_year_ck CHECK (model_year IS NULL OR model_year BETWEEN 1886 AND 9999),
  CONSTRAINT vehicle_created_by_fk FOREIGN KEY (created_by)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL,
  CONSTRAINT vehicle_modified_by_fk FOREIGN KEY (last_modified_by)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL
);

CREATE INDEX vehicle__vehicle_year_make_model
  ON ${schema_prefix}mobility.vehicle (model_year, make, model, vehicle_id);

CREATE TABLE ${schema_prefix}mobility.vehicle_decoded_value (
  vehicle_id varchar(128) NOT NULL,
  field_name text NOT NULL,
  field_value text,
  PRIMARY KEY (vehicle_id, field_name),
  CONSTRAINT vehicle_decoded_value_vehicle_fk FOREIGN KEY (vehicle_id)
    REFERENCES ${schema_prefix}mobility.vehicle (vehicle_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}mobility.vin_decode_cache (
  vin varchar(17) PRIMARY KEY,
  body bytea NOT NULL,
  created_on timestamptz NOT NULL,
  decoder_version varchar(64),
  error_code text,
  error_text text,
  expires_on timestamptz NOT NULL,
  last_updated_on timestamptz NOT NULL,
  make text,
  model text,
  model_year integer,
  plant_city text,
  plant_country text,
  plant_state text,
  refreshed_on timestamptz,
  response_vin varchar(17),
  CONSTRAINT vin_decode_cache_expiration_ck CHECK (expires_on >= created_on)
);

CREATE INDEX vin_decode_cache__vin_cache_expiration
  ON ${schema_prefix}mobility.vin_decode_cache (expires_on, vin);

CREATE TABLE ${schema_prefix}mobility.vin_decode_raw_value (
  vin varchar(17) NOT NULL,
  field_name text NOT NULL,
  field_value text,
  PRIMARY KEY (vin, field_name),
  CONSTRAINT vin_decode_raw_cache_fk FOREIGN KEY (vin)
    REFERENCES ${schema_prefix}mobility.vin_decode_cache (vin) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}mobility.nhtsa_import_state (
  import_state_id varchar(128) PRIMARY KEY,
  calls_on_date date,
  calls_today integer NOT NULL DEFAULT 0,
  disabled_until timestamptz,
  forbidden_on timestamptz,
  last_attempt_on timestamptz,
  last_failure_on timestamptz,
  last_failure_status integer,
  lifetime_calls bigint NOT NULL DEFAULT 0,
  lifetime_vins_processed bigint NOT NULL DEFAULT 0,
  notes text,
  permanently_disabled boolean NOT NULL DEFAULT false,
  vins_processed_today integer NOT NULL DEFAULT 0,
  CONSTRAINT nhtsa_import_state_counts_ck CHECK (
    calls_today >= 0 AND lifetime_calls >= 0
    AND lifetime_vins_processed >= 0 AND vins_processed_today >= 0)
);

CREATE TABLE ${schema_prefix}mobility.random_vin_import_state (
  import_state_id varchar(128) PRIMARY KEY,
  calls_on_date date,
  calls_today integer NOT NULL DEFAULT 0,
  disabled_until timestamptz,
  forbidden_on timestamptz,
  last_attempt_on timestamptz,
  last_failure_on timestamptz,
  last_failure_status integer,
  lifetime_calls bigint NOT NULL DEFAULT 0,
  lifetime_vins_processed bigint NOT NULL DEFAULT 0,
  notes text,
  permanently_disabled boolean NOT NULL DEFAULT false,
  robots_allowed boolean NOT NULL DEFAULT false,
  robots_checked_on timestamptz,
  robots_fail_closed boolean NOT NULL DEFAULT true,
  robots_reason text,
  vins_processed_today integer NOT NULL DEFAULT 0,
  CONSTRAINT random_vin_import_state_counts_ck CHECK (
    calls_today >= 0 AND lifetime_calls >= 0
    AND lifetime_vins_processed >= 0 AND vins_processed_today >= 0)
);

CREATE TABLE ${schema_prefix}mobility.zip_coordinate (
  zip_code varchar(16) PRIMARY KEY,
  latitude numeric(9, 6) NOT NULL,
  longitude numeric(9, 6) NOT NULL,
  source text NOT NULL,
  source_year integer NOT NULL,
  created_on timestamptz NOT NULL,
  last_updated_on timestamptz NOT NULL,
  CONSTRAINT zip_coordinate_latitude_ck CHECK (latitude BETWEEN -90 AND 90),
  CONSTRAINT zip_coordinate_longitude_ck CHECK (longitude BETWEEN -180 AND 180)
);

CREATE TABLE ${schema_prefix}mobility.zip_import_state (
  import_state_id varchar(128) PRIMARY KEY,
  checksum varchar(128) NOT NULL,
  imported_on timestamptz NOT NULL,
  source text NOT NULL,
  source_year integer NOT NULL,
  result_checksum varchar(128) NOT NULL,
  result_created integer NOT NULL,
  result_deleted integer NOT NULL,
  result_imported_on timestamptz NOT NULL,
  result_no_op boolean NOT NULL,
  result_processed integer NOT NULL,
  result_source text NOT NULL,
  result_source_year integer NOT NULL,
  result_unchanged integer NOT NULL,
  result_updated integer NOT NULL,
  CONSTRAINT zip_import_state_counts_ck CHECK (
    result_created >= 0 AND result_deleted >= 0 AND result_processed >= 0
    AND result_unchanged >= 0 AND result_updated >= 0)
);

CREATE TABLE ${schema_prefix}lunch.restaurant (
  restaurant_id varchar(128) PRIMARY KEY,
  city text,
  country text,
  county text,
  created_by varchar(128),
  created_on timestamptz,
  cuisine text,
  dedupe_key text NOT NULL,
  display_name text NOT NULL,
  last_modified_by varchar(128),
  last_updated_on timestamptz,
  latitude numeric(9, 6),
  longitude numeric(9, 6),
  normalized_name text,
  phone_number text,
  postal_code text,
  region text,
  search_city text NOT NULL,
  search_state text NOT NULL,
  source_amenity text,
  street_1 text,
  street_2 text,
  website text,
  CONSTRAINT restaurant_dedupe_key_uk UNIQUE (dedupe_key),
  CONSTRAINT restaurant_coordinate_pair_ck CHECK (
    (latitude IS NULL) = (longitude IS NULL)),
  CONSTRAINT restaurant_latitude_ck CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
  CONSTRAINT restaurant_longitude_ck CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
  CONSTRAINT restaurant_created_by_fk FOREIGN KEY (created_by)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL,
  CONSTRAINT restaurant_modified_by_fk FOREIGN KEY (last_modified_by)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL
);

CREATE INDEX restaurant__restaurant_inventory_location_name
  ON ${schema_prefix}lunch.restaurant
    (country, region, city, normalized_name, restaurant_id);
CREATE INDEX restaurant__restaurant_search_location
  ON ${schema_prefix}lunch.restaurant (search_state, search_city, cuisine, restaurant_id);

CREATE TABLE ${schema_prefix}lunch.restaurant_vote (
  restaurant_vote_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  restaurant_id varchar(128) NOT NULL,
  vote_value integer NOT NULL,
  created_on timestamptz NOT NULL,
  last_updated_on timestamptz NOT NULL,
  CONSTRAINT restaurant_vote_account_restaurant_uk UNIQUE (account_id, restaurant_id),
  CONSTRAINT restaurant_vote_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE CASCADE,
  CONSTRAINT restaurant_vote_restaurant_fk FOREIGN KEY (restaurant_id)
    REFERENCES ${schema_prefix}lunch.restaurant (restaurant_id) ON DELETE CASCADE,
  CONSTRAINT restaurant_vote_value_ck CHECK (vote_value BETWEEN -1 AND 1)
);

CREATE TABLE ${schema_prefix}lunch.restaurant_favorite (
  restaurant_favorite_id varchar(128) PRIMARY KEY,
  account_id varchar(128) NOT NULL,
  restaurant_id varchar(128) NOT NULL,
  created_on timestamptz NOT NULL,
  CONSTRAINT restaurant_favorite_account_restaurant_uk UNIQUE (account_id, restaurant_id),
  CONSTRAINT restaurant_favorite_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE CASCADE,
  CONSTRAINT restaurant_favorite_restaurant_fk FOREIGN KEY (restaurant_id)
    REFERENCES ${schema_prefix}lunch.restaurant (restaurant_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}lunch.lunch_preference (
  account_id varchar(128) PRIMARY KEY,
  radius_miles numeric(8, 2) NOT NULL,
  CONSTRAINT lunch_preference_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE CASCADE,
  CONSTRAINT lunch_preference_radius_positive_ck CHECK (radius_miles > 0)
);

CREATE TABLE ${schema_prefix}lunch.lunch_preference_cuisine (
  account_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  cuisine text NOT NULL,
  PRIMARY KEY (account_id, ordinal),
  CONSTRAINT lunch_preference_cuisine_preference_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}lunch.lunch_preference (account_id) ON DELETE CASCADE,
  CONSTRAINT lunch_preference_cuisine_ordinal_ck CHECK (ordinal >= 0)
);

CREATE TABLE ${schema_prefix}lunch.lunch_session (
  lunch_session_id varchar(128) PRIMARY KEY,
  active_until timestamptz NOT NULL,
  created_by_account_id varchar(128) NOT NULL,
  created_by_username varchar(128) NOT NULL,
  created_on timestamptz NOT NULL,
  delete_on timestamptz NOT NULL,
  last_updated_on timestamptz NOT NULL,
  restaurant_reset_count bigint NOT NULL DEFAULT 0,
  revision bigint NOT NULL DEFAULT 0,
  CONSTRAINT lunch_session_creator_fk FOREIGN KEY (created_by_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT lunch_session_revision_ck CHECK (revision >= 0 AND restaurant_reset_count >= 0),
  CONSTRAINT lunch_session_lifetime_ck CHECK (delete_on >= active_until)
);

CREATE INDEX lunch_session__lunch_session_active
  ON ${schema_prefix}lunch.lunch_session (active_until, lunch_session_id);
CREATE INDEX lunch_session__lunch_session_delete
  ON ${schema_prefix}lunch.lunch_session (delete_on, lunch_session_id);

CREATE TABLE ${schema_prefix}lunch.lunch_session_participant (
  lunch_session_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  account_id varchar(128) NOT NULL,
  username varchar(128) NOT NULL,
  PRIMARY KEY (lunch_session_id, ordinal),
  CONSTRAINT lunch_session_participant_account_uk UNIQUE (lunch_session_id, account_id),
  CONSTRAINT lunch_session_participant_session_fk FOREIGN KEY (lunch_session_id)
    REFERENCES ${schema_prefix}lunch.lunch_session (lunch_session_id) ON DELETE CASCADE,
  CONSTRAINT lunch_session_participant_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}lunch.lunch_session_restaurant (
  lunch_session_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  restaurant_id varchar(128) NOT NULL,
  PRIMARY KEY (lunch_session_id, ordinal),
  CONSTRAINT lunch_session_restaurant_uk UNIQUE (lunch_session_id, restaurant_id),
  CONSTRAINT lunch_session_restaurant_session_fk FOREIGN KEY (lunch_session_id)
    REFERENCES ${schema_prefix}lunch.lunch_session (lunch_session_id) ON DELETE CASCADE,
  CONSTRAINT lunch_session_restaurant_restaurant_fk FOREIGN KEY (restaurant_id)
    REFERENCES ${schema_prefix}lunch.restaurant (restaurant_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}lunch.lunch_session_vote (
  lunch_session_id varchar(128) NOT NULL,
  account_id varchar(128) NOT NULL,
  restaurant_id varchar(128) NOT NULL,
  PRIMARY KEY (lunch_session_id, account_id),
  CONSTRAINT lunch_session_vote_session_fk FOREIGN KEY (lunch_session_id)
    REFERENCES ${schema_prefix}lunch.lunch_session (lunch_session_id) ON DELETE CASCADE,
  CONSTRAINT lunch_session_vote_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT lunch_session_vote_restaurant_fk FOREIGN KEY (restaurant_id)
    REFERENCES ${schema_prefix}lunch.restaurant (restaurant_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}lunch.lunch_session_reset_audit (
  lunch_session_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  account_id varchar(128) NOT NULL,
  username varchar(128) NOT NULL,
  occurred_on timestamptz NOT NULL,
  revision bigint NOT NULL,
  PRIMARY KEY (lunch_session_id, ordinal),
  CONSTRAINT lunch_session_reset_audit_session_fk FOREIGN KEY (lunch_session_id)
    REFERENCES ${schema_prefix}lunch.lunch_session (lunch_session_id) ON DELETE CASCADE,
  CONSTRAINT lunch_session_reset_audit_account_fk FOREIGN KEY (account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}lunch.lunch_session_reset_restaurant (
  lunch_session_id varchar(128) NOT NULL,
  reset_ordinal integer NOT NULL,
  restaurant_ordinal integer NOT NULL,
  restaurant_id varchar(128) NOT NULL,
  PRIMARY KEY (lunch_session_id, reset_ordinal, restaurant_ordinal),
  CONSTRAINT lunch_session_reset_restaurant_audit_fk
    FOREIGN KEY (lunch_session_id, reset_ordinal)
    REFERENCES ${schema_prefix}lunch.lunch_session_reset_audit (lunch_session_id, ordinal)
    ON DELETE CASCADE,
  CONSTRAINT lunch_session_reset_restaurant_restaurant_fk FOREIGN KEY (restaurant_id)
    REFERENCES ${schema_prefix}lunch.restaurant (restaurant_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}lunch.daily_lunch_picks (
  daily_lunch_picks_id varchar(128) PRIMARY KEY,
  pick_date date NOT NULL,
  generated_on timestamptz NOT NULL,
  CONSTRAINT daily_lunch_picks_date_uk UNIQUE (pick_date)
);

CREATE TABLE ${schema_prefix}lunch.daily_lunch_pick_restaurant (
  daily_lunch_picks_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  restaurant_id varchar(128) NOT NULL,
  PRIMARY KEY (daily_lunch_picks_id, ordinal),
  CONSTRAINT daily_lunch_pick_parent_fk FOREIGN KEY (daily_lunch_picks_id)
    REFERENCES ${schema_prefix}lunch.daily_lunch_picks (daily_lunch_picks_id) ON DELETE CASCADE,
  CONSTRAINT daily_lunch_pick_restaurant_fk FOREIGN KEY (restaurant_id)
    REFERENCES ${schema_prefix}lunch.restaurant (restaurant_id) ON DELETE RESTRICT
);

CREATE TABLE ${schema_prefix}lunch.restaurant_import_state (
  import_state_id varchar(128) PRIMARY KEY,
  actor_account_id varchar(128),
  last_completed_month date,
  last_completed_on timestamptz,
  last_error_category text,
  last_failed_on timestamptz,
  last_failure_message text,
  last_skipped_on timestamptz,
  last_skipped_trigger text,
  last_started_on timestamptz,
  result_fetched integer,
  result_imported integer,
  result_skipped_existing integer,
  result_skipped_invalid integer,
  result_source text,
  result_updated integer,
  status varchar(64) NOT NULL,
  trigger_name text,
  CONSTRAINT restaurant_import_state_actor_fk FOREIGN KEY (actor_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL,
  CONSTRAINT restaurant_import_state_counts_ck CHECK (
    (result_fetched IS NULL OR result_fetched >= 0)
    AND (result_imported IS NULL OR result_imported >= 0)
    AND (result_skipped_existing IS NULL OR result_skipped_existing >= 0)
    AND (result_skipped_invalid IS NULL OR result_skipped_invalid >= 0)
    AND (result_updated IS NULL OR result_updated >= 0))
);

CREATE TABLE ${schema_prefix}lunch.restaurant_import_preview (
  import_preview_id varchar(128) PRIMARY KEY,
  actor_account_id varchar(128) NOT NULL,
  checksum varchar(128) NOT NULL,
  consumed_on timestamptz,
  created_count integer NOT NULL,
  created_on timestamptz NOT NULL,
  deleted_count integer NOT NULL,
  expires_on timestamptz NOT NULL,
  fetched_count integer NOT NULL,
  invalid_count integer NOT NULL,
  unchanged_count integer NOT NULL,
  updated_count integer NOT NULL,
  CONSTRAINT restaurant_import_preview_actor_fk FOREIGN KEY (actor_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE RESTRICT,
  CONSTRAINT restaurant_import_preview_counts_ck CHECK (
    created_count >= 0 AND deleted_count >= 0 AND fetched_count >= 0
    AND invalid_count >= 0 AND unchanged_count >= 0 AND updated_count >= 0)
);

CREATE INDEX restaurant_import_preview__restaurant_import_preview_expiration
  ON ${schema_prefix}lunch.restaurant_import_preview (expires_on, import_preview_id);

ALTER TABLE ${schema_prefix}communication.notification
  ADD CONSTRAINT notification_lunch_session_fk FOREIGN KEY (lunch_session_id)
    REFERENCES ${schema_prefix}lunch.lunch_session (lunch_session_id) ON DELETE SET NULL;

CREATE TABLE ${schema_prefix}canes.price_snapshot (
  price_snapshot_id varchar(128) PRIMARY KEY,
  week_start_date date NOT NULL,
  collected_on timestamptz NOT NULL,
  average_price numeric(12, 2) NOT NULL,
  currency varchar(3) NOT NULL,
  successful_metro_count integer NOT NULL,
  total_metro_count integer NOT NULL,
  verified_metro_count integer NOT NULL,
  provisional_metro_count integer NOT NULL,
  excluded_metro_count integer NOT NULL,
  CONSTRAINT price_snapshot_week_uk UNIQUE (week_start_date),
  CONSTRAINT price_snapshot_average_nonnegative_ck CHECK (average_price >= 0),
  CONSTRAINT price_snapshot_counts_ck CHECK (
    successful_metro_count >= 0 AND total_metro_count >= 0
    AND verified_metro_count >= 0 AND provisional_metro_count >= 0
    AND excluded_metro_count >= 0
    AND successful_metro_count <= total_metro_count)
);

CREATE TABLE ${schema_prefix}canes.metro_price (
  price_snapshot_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  metro_name text NOT NULL,
  city text,
  region text,
  restaurant_ref text,
  restaurant_name text,
  address text,
  source_url text,
  price numeric(12, 2),
  currency varchar(3),
  status varchar(64) NOT NULL,
  source_name text,
  quality_status varchar(64),
  confidence_level varchar(64),
  raw_response_hash varchar(128),
  matched_item_name text,
  failure_reason text,
  review_note text,
  collected_on timestamptz NOT NULL,
  source_fetched_on timestamptz,
  reviewed_on timestamptz,
  PRIMARY KEY (price_snapshot_id, ordinal),
  CONSTRAINT metro_price_snapshot_fk FOREIGN KEY (price_snapshot_id)
    REFERENCES ${schema_prefix}canes.price_snapshot (price_snapshot_id) ON DELETE CASCADE,
  CONSTRAINT metro_price_amount_nonnegative_ck CHECK (price IS NULL OR price >= 0)
);

CREATE INDEX metro_price__metro_price_location
  ON ${schema_prefix}canes.metro_price
    (metro_name, city, region, price_snapshot_id, ordinal);

CREATE TABLE ${schema_prefix}platform.application_lease (
  lease_name varchar(128) PRIMARY KEY,
  owner_token varchar(128) NOT NULL,
  fence_token bigint NOT NULL DEFAULT 1,
  acquired_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  CONSTRAINT application_lease_fence_positive_ck CHECK (fence_token > 0)
);

CREATE INDEX application_lease__application_lease_expiration
  ON ${schema_prefix}platform.application_lease (expires_at, lease_name);

CREATE TABLE ${schema_prefix}platform.scheduled_collector_run (
  collector_run_id varchar(128) PRIMARY KEY,
  collector_name varchar(128) NOT NULL,
  owner_token varchar(128) NOT NULL,
  status varchar(64) NOT NULL,
  started_on timestamptz NOT NULL,
  completed_on timestamptz,
  error_category text,
  CONSTRAINT scheduled_collector_run_completion_ck CHECK (
    completed_on IS NULL OR completed_on >= started_on)
);

CREATE INDEX scheduled_collector_run__scheduled_collector_status_completed
  ON ${schema_prefix}platform.scheduled_collector_run
    (collector_name, status, completed_on DESC, collector_run_id);

CREATE TABLE ${schema_prefix}platform.application_migration_record (
  migration_record_id varchar(128) PRIMARY KEY,
  checksum varchar(128) NOT NULL,
  description text NOT NULL,
  status varchar(64) NOT NULL,
  owner_token varchar(128) NOT NULL,
  started_at timestamptz NOT NULL,
  completed_at timestamptz,
  failure_category text,
  CONSTRAINT application_migration_record_completion_ck CHECK (
    completed_at IS NULL OR completed_at >= started_at)
);

CREATE INDEX application_migration_record__migration_status_completed
  ON ${schema_prefix}platform.application_migration_record
    (status, completed_at DESC, migration_record_id);

CREATE TABLE ${schema_prefix}platform.domain_collection_cutover (
  cutover_id varchar(128) PRIMARY KEY,
  state varchar(64) NOT NULL,
  manifest_digest varchar(128) NOT NULL,
  owner_token varchar(128) NOT NULL,
  release_commit varchar(128) NOT NULL,
  backup_identity text NOT NULL,
  evidence_digest varchar(128) NOT NULL,
  revision integer NOT NULL,
  stage_index integer NOT NULL,
  publish_index integer NOT NULL,
  drop_index integer NOT NULL,
  completed boolean NOT NULL,
  legacy_dropped boolean NOT NULL,
  intent text,
  CONSTRAINT domain_collection_cutover_indexes_ck CHECK (
    revision >= 0 AND stage_index >= 0 AND publish_index >= 0 AND drop_index >= 0)
);

CREATE TABLE ${schema_prefix}platform.domain_collection_cutover_source (
  cutover_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  source_name text NOT NULL,
  PRIMARY KEY (cutover_id, ordinal),
  CONSTRAINT domain_collection_cutover_source_parent_fk FOREIGN KEY (cutover_id)
    REFERENCES ${schema_prefix}platform.domain_collection_cutover (cutover_id) ON DELETE CASCADE
);

CREATE TABLE ${schema_prefix}platform.domain_collection_cutover_metric (
  cutover_id varchar(128) NOT NULL,
  ordinal integer NOT NULL,
  source_kind varchar(128) NOT NULL,
  source_count bigint NOT NULL,
  checksum varchar(128) NOT NULL,
  PRIMARY KEY (cutover_id, ordinal),
  CONSTRAINT domain_collection_cutover_metric_kind_uk UNIQUE (cutover_id, source_kind),
  CONSTRAINT domain_collection_cutover_metric_parent_fk FOREIGN KEY (cutover_id)
    REFERENCES ${schema_prefix}platform.domain_collection_cutover (cutover_id) ON DELETE CASCADE,
  CONSTRAINT domain_collection_cutover_metric_count_ck CHECK (source_count >= 0)
);

CREATE TABLE ${schema_prefix}platform.admin_activity (
  admin_activity_id varchar(128) PRIMARY KEY,
  actor_account_id varchar(128),
  actor_username varchar(128) NOT NULL,
  action varchar(128) NOT NULL,
  target_type varchar(128) NOT NULL,
  target_id varchar(128) NOT NULL,
  target_label text NOT NULL,
  reason text NOT NULL,
  message text NOT NULL,
  created_on timestamptz NOT NULL,
  CONSTRAINT admin_activity_actor_fk FOREIGN KEY (actor_account_id)
    REFERENCES ${schema_prefix}identity.account (account_id) ON DELETE SET NULL
);

CREATE INDEX admin_activity__admin_activity_created
  ON ${schema_prefix}platform.admin_activity (created_on DESC, admin_activity_id);
CREATE INDEX admin_activity__admin_activity_target
  ON ${schema_prefix}platform.admin_activity
    (target_type, target_id, created_on DESC, admin_activity_id);

CREATE TABLE ${schema_prefix}platform.admin_activity_value (
  admin_activity_id varchar(128) NOT NULL,
  partition_name varchar(32) NOT NULL,
  value_key text NOT NULL,
  value_text text,
  PRIMARY KEY (admin_activity_id, partition_name, value_key),
  CONSTRAINT admin_activity_value_parent_fk FOREIGN KEY (admin_activity_id)
    REFERENCES ${schema_prefix}platform.admin_activity (admin_activity_id) ON DELETE CASCADE,
  CONSTRAINT admin_activity_value_partition_ck CHECK (
    partition_name IN ('before', 'after', 'metadata'))
);

CREATE TABLE ${schema_prefix}platform.pending_action (
  pending_action_id varchar(128) PRIMARY KEY,
  action varchar(64) NOT NULL,
  accepted_at timestamptz NOT NULL,
  execute_at timestamptz NOT NULL,
  CONSTRAINT pending_action_execute_order_ck CHECK (execute_at >= accepted_at)
);

CREATE INDEX pending_action__pending_action_execute
  ON ${schema_prefix}platform.pending_action (execute_at, pending_action_id);

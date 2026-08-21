ALTER TABLE ${schema_prefix}mobility.vin_decode_cache
  ADD COLUMN response_present boolean NOT NULL DEFAULT false;

UPDATE ${schema_prefix}mobility.vin_decode_cache cache
SET response_present = true
WHERE cache.response_vin IS NOT NULL
   OR cache.make IS NOT NULL
   OR cache.model IS NOT NULL
   OR cache.model_year IS NOT NULL
   OR cache.body IS NOT NULL
   OR cache.plant_city IS NOT NULL
   OR cache.plant_state IS NOT NULL
   OR cache.plant_country IS NOT NULL
   OR cache.error_code IS NOT NULL
   OR cache.error_text IS NOT NULL
   OR EXISTS (
       SELECT 1
       FROM ${schema_prefix}mobility.vin_decode_raw_value raw_value
       WHERE raw_value.vin = cache.vin);

ALTER TABLE ${schema_prefix}mobility.nhtsa_import_state
  ALTER COLUMN calls_today DROP NOT NULL,
  ALTER COLUMN calls_today DROP DEFAULT,
  ALTER COLUMN lifetime_calls DROP NOT NULL,
  ALTER COLUMN lifetime_calls DROP DEFAULT,
  ALTER COLUMN lifetime_vins_processed DROP NOT NULL,
  ALTER COLUMN lifetime_vins_processed DROP DEFAULT,
  ALTER COLUMN permanently_disabled DROP NOT NULL,
  ALTER COLUMN permanently_disabled DROP DEFAULT,
  ALTER COLUMN vins_processed_today DROP NOT NULL,
  ALTER COLUMN vins_processed_today DROP DEFAULT;

ALTER TABLE ${schema_prefix}mobility.random_vin_import_state
  ADD COLUMN robots_policy_present boolean NOT NULL DEFAULT false,
  ALTER COLUMN calls_today DROP NOT NULL,
  ALTER COLUMN calls_today DROP DEFAULT,
  ALTER COLUMN lifetime_calls DROP NOT NULL,
  ALTER COLUMN lifetime_calls DROP DEFAULT,
  ALTER COLUMN lifetime_vins_processed DROP NOT NULL,
  ALTER COLUMN lifetime_vins_processed DROP DEFAULT,
  ALTER COLUMN permanently_disabled DROP NOT NULL,
  ALTER COLUMN permanently_disabled DROP DEFAULT,
  ALTER COLUMN robots_allowed DROP NOT NULL,
  ALTER COLUMN robots_allowed DROP DEFAULT,
  ALTER COLUMN robots_fail_closed DROP NOT NULL,
  ALTER COLUMN robots_fail_closed DROP DEFAULT,
  ALTER COLUMN vins_processed_today DROP NOT NULL,
  ALTER COLUMN vins_processed_today DROP DEFAULT;

UPDATE ${schema_prefix}mobility.random_vin_import_state
SET robots_policy_present = true
WHERE robots_checked_on IS NOT NULL
   OR robots_reason IS NOT NULL
   OR robots_allowed
   OR NOT robots_fail_closed;

ALTER TABLE ${schema_prefix}lunch.restaurant_vote
  ALTER COLUMN vote_value DROP NOT NULL;

ALTER TABLE ${schema_prefix}lunch.restaurant
  DROP CONSTRAINT restaurant_dedupe_key_uk;

ALTER TABLE ${schema_prefix}platform.admin_activity
  ADD COLUMN before_values_present boolean NOT NULL DEFAULT false,
  ADD COLUMN after_values_present boolean NOT NULL DEFAULT false,
  ADD COLUMN metadata_present boolean NOT NULL DEFAULT false,
  ALTER COLUMN target_label DROP NOT NULL,
  ALTER COLUMN reason DROP NOT NULL,
  ALTER COLUMN message DROP NOT NULL;

UPDATE ${schema_prefix}platform.admin_activity activity
SET before_values_present = EXISTS (
      SELECT 1 FROM ${schema_prefix}platform.admin_activity_value value
      WHERE value.admin_activity_id = activity.admin_activity_id
        AND value.partition_name = 'before'),
    after_values_present = EXISTS (
      SELECT 1 FROM ${schema_prefix}platform.admin_activity_value value
      WHERE value.admin_activity_id = activity.admin_activity_id
        AND value.partition_name = 'after'),
    metadata_present = EXISTS (
      SELECT 1 FROM ${schema_prefix}platform.admin_activity_value value
      WHERE value.admin_activity_id = activity.admin_activity_id
        AND value.partition_name = 'metadata');

ALTER TABLE ${schema_prefix}mobility.vin_decode_cache
  ADD COLUMN raw_decoded_values_present boolean NOT NULL DEFAULT false;

UPDATE ${schema_prefix}mobility.vin_decode_cache cache
SET raw_decoded_values_present = true
WHERE EXISTS (
    SELECT 1
    FROM ${schema_prefix}mobility.vin_decode_raw_value raw_value
    WHERE raw_value.vin = cache.vin);

ALTER TABLE ${schema_prefix}mobility.vin_decode_cache
  ADD CONSTRAINT vin_decode_cache_raw_presence_requires_response_ck
  CHECK (NOT raw_decoded_values_present OR response_present);

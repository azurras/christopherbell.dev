ALTER TABLE ${schema_prefix}platform.application_lease
  ALTER COLUMN owner_token DROP NOT NULL,
  ALTER COLUMN fence_token DROP NOT NULL;

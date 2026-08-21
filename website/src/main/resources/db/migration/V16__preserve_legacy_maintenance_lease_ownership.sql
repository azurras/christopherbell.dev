ALTER TABLE ${schema_prefix}shared_folder.maintenance_lease
    ALTER COLUMN owner_token DROP NOT NULL,
    ALTER COLUMN fence_token DROP NOT NULL;

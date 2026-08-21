ALTER TABLE ${schema_prefix}shared_folder.audit_event
  ALTER COLUMN client_ip TYPE varchar(64) USING client_ip::text;

ALTER TABLE ${schema_prefix}identity.deleted_account_pseudonym
  DROP CONSTRAINT deleted_account_pseudonym_format_ck,
  ADD CONSTRAINT deleted_account_pseudonym_format_ck CHECK (
    pseudonym_id ~ '^deleted:[0-9a-f]{12}$'
    OR pseudonym_id IN ('system', 'unknown'));

INSERT INTO ${schema_prefix}identity.deleted_account_pseudonym (pseudonym_id)
VALUES ('system'), ('unknown')
ON CONFLICT (pseudonym_id) DO NOTHING;

ALTER TABLE ${schema_prefix}identity.account
  DROP CONSTRAINT account_normalized_email_key;

CREATE INDEX account__normalized_email_lookup
  ON ${schema_prefix}identity.account (normalized_email, account_id);

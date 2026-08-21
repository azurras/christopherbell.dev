-- Retained audit/report identifiers are pseudonyms, not live account references.
-- Keeping these foreign keys would either erase the identifier or prevent deletion.
ALTER TABLE ${schema_prefix}social.post_edit_audit
  DROP CONSTRAINT post_edit_audit_editor_fk;

ALTER TABLE ${schema_prefix}social.post_report
  DROP CONSTRAINT post_report_reported_account_fk,
  DROP CONSTRAINT post_report_reporter_account_fk;

ALTER TABLE ${schema_prefix}platform.admin_activity
  DROP CONSTRAINT admin_activity_actor_fk;

ALTER TABLE ${schema_prefix}shared_folder.audit_event
  DROP CONSTRAINT audit_event_account_fk,
  ALTER COLUMN client_ip DROP NOT NULL;

ALTER TABLE ${schema_prefix}shared_folder.recycle_item
  DROP CONSTRAINT recycle_item_account_fk;

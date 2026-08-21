ALTER TABLE ${schema_prefix}communication.notification
  DROP CONSTRAINT notification_post_fk,
  DROP CONSTRAINT notification_message_fk,
  DROP CONSTRAINT notification_lunch_session_fk;

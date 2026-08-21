ALTER TABLE ${schema_prefix}music.runtime_state
  ADD CONSTRAINT runtime_state_kind_uk UNIQUE (state_kind);

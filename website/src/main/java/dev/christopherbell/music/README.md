# Music

Owns the private music catalog, playlists, metadata edits, queue/radio state, playback, and access
auditing.

## Migration Boundary

`api.MusicMigrationVerifier` publishes narrow real-adapter parity operations for catalog filters
and facets, playlist reconstruction, metadata expiry, queue/radio state, history ordering, access
attempt ordering, and rollback-contained expiry cleanup during the guarded
MongoDB-to-PostgreSQL cutover. It is verification support, not a runtime persistence port.

## Update This Doc

Update this README when music ownership, adapter boundaries, or cutover verification behavior
changes.

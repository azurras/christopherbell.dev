# Music

Owns the private music catalog, playlists, metadata edits, queue/radio state, playback, and access
auditing.

## Update This Doc

Update this README when music ownership, adapter boundaries, or cutover verification behavior
changes.

Catalog scans and metadata edits refresh track metadata without replacing stored listener preferences.
Favorites and radio exclusions are initialized on first insertion and subsequently changed only by the
preference compare-and-set operation. Delayed successful probes, failed probes, and missing-file
marks preserve preferences accepted after their original catalog read.

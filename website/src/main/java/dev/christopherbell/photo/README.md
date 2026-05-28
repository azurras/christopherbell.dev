# Photo

Owns photography/photo listing behavior.

## What Lives Here

- Photo controllers and services.
- Photo metadata models under `model`.
- Read-side behavior for photography pages.

## Package Shape

This package remains flat while it only owns read-only gallery data. Create a
`gallery` subpackage when uploads, albums, moderation, or permissions are added.

## Update This Doc

Update this README when photo metadata, photo source locations, or photo page/API behavior changes.

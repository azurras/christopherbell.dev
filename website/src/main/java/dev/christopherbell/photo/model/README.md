# Photo Models

Owns photography data shapes.

## What Lives Here

- `Photo` describes an individual photo asset.
- `PhotoProperties` binds configured photo collections.
- `PhotoResponse` is the API response shape for gallery data.

## Design Notes

- Photo data is configuration-backed, not user-generated.
- Keep response fields close to what the gallery needs so the client does not
  infer paths or metadata from filenames.

## Update This Doc

Update this README when photo metadata, configuration, or gallery response fields
change.

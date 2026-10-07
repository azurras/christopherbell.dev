# Blog Models

Owns data shapes for the legacy blog feature.

## What Lives Here

- `Post` represents a blog post loaded from configured content.
- `BlogResponse` is the API response shape for blog reads.
- `BlogProperties` binds blog configuration.

## Design Notes

- Blog models are separate from Void post models. Do not reuse one for the other;
  they have different storage and behavior.
- `Post` is a record that rejects a missing id or title, so a broken entry fails
  startup. Missing tags become an empty list.
- Keep configured blog data read-only unless the blog feature gains a real
  persistence workflow.

## Update This Doc

Update this README when blog configuration, response fields, or storage behavior
changes.

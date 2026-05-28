# Post Preview

Owns link preview extraction and fetching for post text.

## What Lives Here

- `PostLinkPreviewService` extracts distinct HTTP/HTTPS links from post text.
- `PostLinkPreviewClient` defines the metadata fetch boundary.
- `JsoupPostLinkPreviewClient` fetches public HTML metadata with SSRF guards.

## Design Notes

Preview failures must not block post creation. Store preview metadata once on the post and let browser rendering fall back to clickable raw links when no preview is available.

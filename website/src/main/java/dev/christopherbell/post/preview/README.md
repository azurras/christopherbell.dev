# Post Preview

Owns link preview extraction and fetching for post text.

## What Lives Here

- `PostLinkPreviewService` extracts distinct HTTP/HTTPS links from post text.
- `PostLinkPreviewClient` defines the metadata fetch boundary.
- `PostLinkPreviewDestinationPolicy` resolves and rejects non-public initial and
  redirect destinations, including unsafe schemes, userinfo, private,
  link-local, multicast, documentation, and reserved address space.
- `BoundedLinkPreviewHttpClient` performs manual redirects and bounded HTML or
  XHTML reads using `posts.link-previews.connect-timeout`, `request-timeout`,
  `overall-timeout`, `max-redirects`, `max-response-bytes`, and
  `allowed-content-types`.
- `JsoupPostLinkPreviewClient` parses already-fetched bytes and caps stored
  title, description, and image URL lengths. It does not run JavaScript or
  proxy image bytes.
- `PostLinkPreviewService` resolves at most
  `posts.link-previews.max-urls-per-post` distinct URLs and stores expiring
  success and safe failure cache entries using `success-ttl` and `failure-ttl`.

## Design Notes

Preview failures must not block post creation. The Mongo cache suppresses
repeated outbound failures for a short bounded period; application reads still
check `expiresOn` because TTL cleanup is asynchronous. Store resolved preview
metadata on the post and let browser rendering fall back to clickable raw links
when no preview is available.

# Post Preview

Owns link preview extraction and fetching for post text.

## What Lives Here

- `PostLinkPreviewService` extracts distinct HTTP/HTTPS links from post text.
- `PostLinkPreviewClient` defines the metadata fetch boundary.
- `PostLinkPreviewDestinationPolicy` resolves each initial or redirect hop once,
  rejects the whole answer set when any address is non-public, and returns a
  destination pinned to one address from that exact validated set.
- `BoundedLinkPreviewHttpClient` performs manual redirects and bounded HTML or
  XHTML reads through a fresh pinned-address connection per hop. The transport
  retains the original Host header, SNI name, and TLS hostname verification and
  disables proxy use, automatic redirects, connection reuse, and retries. Bounds
  come from `posts.link-previews.connect-timeout`, `request-timeout`,
  `overall-timeout`, `max-redirects`, `max-response-bytes`, and
  `allowed-content-types`.
- `JsoupPostLinkPreviewClient` parses already-fetched bytes and caps stored
  title, description, and image URL lengths. Only absolute HTTP(S) image
  metadata is retained; relative, protocol-relative, malformed, and other
  schemes are discarded. It does not run JavaScript or proxy image bytes.
- `PostLinkPreviewService` resolves at most
  `posts.link-previews.max-urls-per-post` distinct URLs and stores expiring
  success and safe failure cache entries using `success-ttl` and `failure-ttl`.

## Design Notes

Preview failures must not block post creation. The Mongo cache suppresses
repeated outbound failures for a short bounded period; application reads still
check `expiresOn` because TTL cleanup is asynchronous. Store resolved preview
metadata on the post and let browser rendering fall back to clickable raw links
when no preview is available.

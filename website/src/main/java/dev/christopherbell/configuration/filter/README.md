# Configuration Filters

Owns cross-cutting servlet filters that are not specific to one feature.

## What Lives Here

- `RateLimitFilter` protects the app from excessive per-client request volume
  using the shared trusted-proxy client IP resolver. Its access-ordered bucket
  store expires inactive entries after the matching rule window and remains
  hard-bounded under active-client churn.
- `RequestSizeLimitFilter` rejects requests that exceed the configured payload
  size limit, including bodies streamed without a trustworthy `Content-Length`.
- `ApiErrorResponseWriter` gives servlet-boundary JSON rejections the same safe
  `Response`/`Message` envelope used by controller advice.

## Design Notes

Rate limits are applied by the first matching `rate-limit.rules` entry.
Defaults are stricter for auth mutations and public VIN decode, broader for API
mutations, and permissive for static assets.
Denied requests include retry, limit, remaining-token, and reset guidance.
Oversized JSON bodies receive `REQUEST_TOO_LARGE` without reflecting body data.

Filters in this package should stay small and deterministic. Add focused filter tests for limit boundaries and bypass behavior when behavior changes.


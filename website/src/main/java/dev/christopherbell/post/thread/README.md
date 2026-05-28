# Post Thread

Owns single-post and thread reads.

## What Lives Here

- `PostThreadService` returns a single post by id and flat root/reply thread lists.
- Thread reads repair expiration metadata and omit expired replies.

## Design Notes

Thread reads use `rootId` so nested replies do not need recursive traversal at request time.


# Hidden Post Threads

Owns per-user thread hiding.

## How It Works

- Hiding any post stores the root post id for that thread.
- Feed reads can exclude posts whose root id matches a hidden root for the
  current account.
- Unhide removes the stored account/root pair.

## Update This Doc

Update this README when hidden-thread persistence, API routes, or feed filtering
rules change.

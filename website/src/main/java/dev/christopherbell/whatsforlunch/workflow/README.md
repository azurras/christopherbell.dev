# What's For Lunch Workflow

Owns workflow scaffolding for What's For Lunch.

## What Lives Here

- Workflow context and command-style orchestration primitives for WFL behavior.
- The WFL-owned execution engine, retry policy, operation, result, and exception
  types under `workflow/engine`.
- Retryable failures use the configured backoff and retry window. Terminal
  failures return immediately, and an expired retry window stops the context.

## Update This Doc

Update this README when workflow steps, context fields, or orchestration responsibilities change.

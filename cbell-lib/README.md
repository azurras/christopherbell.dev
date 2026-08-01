# cbell-lib Module

Reusable Java library that contains shared domain and utility code.

## Building
```bash
./gradlew :cbell-lib:build
```

## Running tests
```bash
./gradlew :cbell-lib:test
```

## Bounded HTTP response bodies

`BoundedResponseBodyReader` is the shared JDK-only boundary for remote response
streams whose callers declare a maximum encoded byte count. It closes every
owned stream and raises `BodyLimitExceededException` before returning an
oversized byte array or decoded string. Feature clients continue to own status,
timeout, parsing, and domain-error translation.

## Workflow retry lifecycle

`WorkflowExecutor.executeWorkflowWithRetry` runs workflows synchronously against
the supplied `RetryPolicy`. Each attempt increments `WorkflowContext.attemptCount`.
Retryable workflow failures use `RetryPolicy.getBackoffTimeInMinutes()` and
`calculateNextRetry(...)` before another attempt is made. Non-retryable terminal
statuses return immediately. When the retry window expires, the context is marked
`STOPPED` and execution stops with `WorkflowStopExecutionException`.

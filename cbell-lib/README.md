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

`BoundedResponseBodyHandlers` is the shared JDK-only boundary for HTTP response
bodies whose callers declare a maximum encoded byte count. Its completion stage
stays tied to the full body so the JDK request timeout remains effective after
headers arrive, and it cancels a response before returning oversized content.
`BoundedResponseBodyReader` applies the same return-size contract to an owned
stream, including bounded decompression. Feature clients continue to own status,
timeout, parsing, and domain-error translation.

## Workflow retry lifecycle

`WorkflowExecutor.executeWorkflowWithRetry` runs workflows synchronously against
the supplied `RetryPolicy`. Each attempt increments `WorkflowContext.attemptCount`.
Retryable workflow failures use `RetryPolicy.getBackoffTimeInMinutes()` and
`calculateNextRetry(...)` before another attempt is made. Non-retryable terminal
statuses return immediately. When the retry window expires, the context is marked
`STOPPED` and execution stops with `WorkflowStopExecutionException`.

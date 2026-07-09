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

## Workflow retry lifecycle

`WorkflowExecutor.executeWorkflowWithRetry` runs workflows synchronously against
the supplied `RetryPolicy`. Each attempt increments `WorkflowContext.attemptCount`.
Retryable workflow failures use `RetryPolicy.getBackoffTimeInMinutes()` and
`calculateNextRetry(...)` before another attempt is made. Non-retryable terminal
statuses return immediately. When the retry window expires, the context is marked
`STOPPED` and execution stops with `WorkflowStopExecutionException`.

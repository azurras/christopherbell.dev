package dev.christopherbell.libs.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.christopherbell.libs.workflow.exception.WorkflowRetryableException;
import dev.christopherbell.libs.workflow.exception.WorkflowStopExecutionException;
import dev.christopherbell.libs.workflow.model.WorkflowContext;
import dev.christopherbell.libs.workflow.model.WorkflowResult;
import dev.christopherbell.libs.workflow.model.WorkflowStatus;
import dev.christopherbell.libs.workflow.operation.OperationResult;
import dev.christopherbell.libs.workflow.operation.OperationStatus;
import dev.christopherbell.libs.workflow.retry.RetryPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkflowExecutorTest {

  @Test
  @DisplayName("Execute operation stores completed status")
  void executeOperation_whenOperationSucceeds_updatesOperationHistory() {
    var executor = new WorkflowExecutor();
    var context = context();

    var result = executor.executeOperation(new NamedSuccessfulOperation(), context);

    assertEquals(OperationStatus.COMPLETED, result.getStatus());
    assertEquals(OperationStatus.COMPLETED, context.getOperationHistory().get("NamedSuccessfulOperation"));
  }

  @Test
  @DisplayName("Execute operation stores failed status when operation throws")
  void executeOperation_whenOperationThrows_returnsFailedResult() {
    var executor = new WorkflowExecutor();
    var context = context();

    var result = executor.executeOperation(new NamedFailingOperation(), context);

    assertEquals(OperationStatus.FAILED, result.getStatus());
    assertEquals(OperationStatus.FAILED, context.getOperationHistory().get("NamedFailingOperation"));
  }

  @Test
  @DisplayName("Execute workflow marks context completed on success")
  void executeWorkflow_whenWorkflowSucceeds_marksContextCompleted() {
    var executor = new WorkflowExecutor();
    var context = context();

    var result = executor.executeWorkflow(ctx -> WorkflowResult.builder()
        .id(UUID.randomUUID())
        .status(WorkflowStatus.COMPLETED)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build(), context);

    assertEquals(WorkflowStatus.COMPLETED, result.getStatus());
    assertEquals(WorkflowStatus.COMPLETED, context.getStatus());
  }

  @Test
  @DisplayName("Execute workflow returns retryable failure for retryable exceptions")
  void executeWorkflow_whenWorkflowThrowsRetryable_returnsRetryableFailure() {
    var result = new WorkflowExecutor().executeWorkflow(
        ctx -> {
          throw new WorkflowRetryableException("retry later");
        },
        context());

    assertEquals(WorkflowStatus.RETRYABLE_FAILURE, result.getStatus());
  }

  @Test
  @DisplayName("Execute workflow stops context for stop exceptions")
  void executeWorkflow_whenWorkflowThrowsStop_returnsStopped() {
    var context = context();

    var result = new WorkflowExecutor().executeWorkflow(
        ctx -> {
          throw new WorkflowStopExecutionException("stop");
        },
        context);

    assertEquals(WorkflowStatus.STOPPED, result.getStatus());
    assertEquals(WorkflowStatus.STOPPED, context.getStatus());
  }

  @Test
  @DisplayName("Execute workflow returns failed for unexpected exceptions")
  void executeWorkflow_whenWorkflowThrowsUnexpected_returnsFailed() {
    var result = new WorkflowExecutor().executeWorkflow(
        ctx -> {
          throw new IllegalStateException("unexpected");
        },
        context());

    assertEquals(WorkflowStatus.FAILED, result.getStatus());
  }

  @Test
  @DisplayName("Execute workflow with retry returns first successful result")
  void executeWorkflowWithRetry_whenWorkflowSucceeds_returnsResult() {
    var retryPolicy = retryPolicy(true);
    var context = context();

    var result = new WorkflowExecutor().executeWorkflowWithRetry(
        retryPolicy,
        ctx -> completedResult(),
        context);

    assertEquals(WorkflowStatus.COMPLETED, result.getStatus());
    assertEquals(1, context.getAttemptCount());
    assertEquals(WorkflowStatus.COMPLETED, context.getStatus());
    assertEquals(List.of(), retryPolicy.retryBackoffs);
  }

  @Test
  @DisplayName("Execute workflow with retry retries retryable failures with configured backoff")
  void executeWorkflowWithRetry_whenWorkflowIsRetryable_retriesWithBackoff() {
    var retryPolicy = retryPolicy(true, true, true);
    var context = context();
    var workflow = new Workflow() {
      private int attempts;

      @Override
      public WorkflowResult execute(WorkflowContext ctx) {
        attempts++;
        if (attempts < 3) {
          throw new WorkflowRetryableException("retry " + attempts);
        }
        return completedResult();
      }
    };

    var result = new WorkflowExecutor().executeWorkflowWithRetry(retryPolicy, workflow, context);

    assertEquals(WorkflowStatus.COMPLETED, result.getStatus());
    assertEquals(3, context.getAttemptCount());
    assertEquals(List.of(5, 5), retryPolicy.retryBackoffs);
  }

  @Test
  @DisplayName("Execute workflow with retry stops when retry window has expired")
  void executeWorkflowWithRetry_whenRetryWindowExpired_stops() {
    var retryPolicy = retryPolicy(false);
    var context = context();

    assertThrows(
        WorkflowStopExecutionException.class,
        () -> new WorkflowExecutor().executeWorkflowWithRetry(retryPolicy, ctx -> completedResult(), context));
    assertEquals(WorkflowStatus.STOPPED, context.getStatus());
  }

  private WorkflowContext context() {
    return WorkflowContext.builder()
        .id(UUID.randomUUID())
        .createdAt(Instant.now())
        .operationHistory(new HashMap<>())
        .status(WorkflowStatus.PENDING)
        .build();
  }

  private static WorkflowResult completedResult() {
    return WorkflowResult.builder()
        .id(UUID.randomUUID())
        .status(WorkflowStatus.COMPLETED)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  private static RecordingRetryPolicy retryPolicy(boolean... retryableAnswers) {
    return new RecordingRetryPolicy(retryableAnswers);
  }

  private static final class RecordingRetryPolicy implements RetryPolicy {
    private final boolean[] retryableAnswers;
    private final List<Integer> retryBackoffs = new ArrayList<>();
    private int retryableChecks;

    private RecordingRetryPolicy(boolean[] retryableAnswers) {
      this.retryableAnswers = retryableAnswers;
    }

    @Override
    public int calculateNextRetry(int backoff) {
      retryBackoffs.add(backoff);
      return backoff;
    }

    @Override
    public int getWorkflowTimeOutInMinutes() {
      return 60;
    }

    @Override
    public int getBackoffTimeInMinutes() {
      return 5;
    }

    @Override
    public boolean isJobStillRetryable(int timeOutInMinutes, Instant startTime) {
      var index = Math.min(retryableChecks, retryableAnswers.length - 1);
      retryableChecks++;
      return retryableAnswers[index];
    }
  }

  private static final class NamedSuccessfulOperation implements dev.christopherbell.libs.workflow.operation.Operation {
    @Override
    public OperationResult execute(WorkflowContext ctx) {
      return OperationResult.builder()
          .id(UUID.randomUUID())
          .status(OperationStatus.COMPLETED)
          .createdAt(Instant.now())
          .updatedAt(Instant.now())
          .build();
    }
  }

  private static final class NamedFailingOperation implements dev.christopherbell.libs.workflow.operation.Operation {
    @Override
    public OperationResult execute(WorkflowContext ctx) {
      throw new IllegalStateException("failed");
    }
  }
}

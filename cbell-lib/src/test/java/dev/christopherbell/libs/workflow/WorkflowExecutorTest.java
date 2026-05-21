package dev.christopherbell.libs.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.christopherbell.libs.workflow.exception.WorkflowRetryableException;
import dev.christopherbell.libs.workflow.exception.WorkflowStopExecutionException;
import dev.christopherbell.libs.workflow.model.WorkflowContext;
import dev.christopherbell.libs.workflow.model.WorkflowResult;
import dev.christopherbell.libs.workflow.model.WorkflowStatus;
import dev.christopherbell.libs.workflow.operation.OperationResult;
import dev.christopherbell.libs.workflow.operation.OperationStatus;
import java.time.Instant;
import java.util.HashMap;
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

  private WorkflowContext context() {
    return WorkflowContext.builder()
        .id(UUID.randomUUID())
        .createdAt(Instant.now())
        .operationHistory(new HashMap<>())
        .status(WorkflowStatus.PENDING)
        .build();
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

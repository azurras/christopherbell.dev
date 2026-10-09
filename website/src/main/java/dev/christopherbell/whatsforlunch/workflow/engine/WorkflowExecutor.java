package dev.christopherbell.whatsforlunch.workflow.engine;

import dev.christopherbell.whatsforlunch.workflow.engine.exception.WorkflowRetryableException;
import dev.christopherbell.whatsforlunch.workflow.engine.exception.WorkflowStopExecutionException;
import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowContext;
import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowResult;
import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowStatus;
import dev.christopherbell.whatsforlunch.workflow.engine.operation.Operation;
import dev.christopherbell.whatsforlunch.workflow.engine.operation.OperationResult;
import dev.christopherbell.whatsforlunch.workflow.engine.operation.OperationStatus;
import dev.christopherbell.whatsforlunch.workflow.engine.retry.RetryPolicy;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Represents the engine responsible for executing workflows.
 */
@Slf4j
@Service
public class WorkflowExecutor implements WorkflowEngine {

  private final Clock clock;

  /**
   * Creates an executor that stamps contexts and results with the given clock.
   *
   * @param clock the application clock
   */
  public WorkflowExecutor(Clock clock) {
    this.clock = clock;
  }

  /**
   * Executes the given operation with the provided context.
   *
   * <p>An operation that throws is recorded as {@link OperationStatus#FAILED} instead of
   * propagating, so one failed operation never aborts the caller.
   *
   * @param operation the operation to be executed
   * @param context the context for the operation execution
   */
  @Override
  public OperationResult executeOperation(Operation operation, WorkflowContext context) {
    var operationName = operation.getOperationName();
    try {
      log.info("Starting execution of operation: {}", operationName);
      var result = operation.execute(context);
      context.getOperationHistory().put(operationName, result.getStatus());
      log.info("Successfully completed execution of operation: {}", operationName);
      return result;
    } catch (RuntimeException failure) {
      log.error(
          "Error occurred during operation execution: {}. Operation: {}",
          failure.getMessage(),
          operationName,
          failure
      );
      context.getOperationHistory().put(operationName, OperationStatus.FAILED);
      var now = clock.instant();
      return OperationResult.builder()
          .id(UUID.randomUUID())
          .createdAt(now)
          .updatedAt(now)
          .status(OperationStatus.FAILED)
          .build();
    }
  }

  @Override
  public WorkflowResult executeWorkflowWithRetry(RetryPolicy retryPolicy, Workflow workflow, WorkflowContext context) {
    var jobTimeout = retryPolicy.getWorkflowTimeOutInMinutes();
    var backOff = retryPolicy.getBackoffTimeInMinutes();
    var startTime = context.getCreatedAt();

    while (retryPolicy.isJobStillRetryable(jobTimeout, startTime)) {
      context.setAttemptCount(context.getAttemptCount() + 1);
      context.setStatus(WorkflowStatus.IN_PROGRESS);
      context.setUpdatedAt(clock.instant());
      var result = executeWorkflow(workflow, context);
      if (result.getStatus() != WorkflowStatus.RETRYABLE_FAILURE) {
        return result;
      }
      var retryDelay = retryPolicy.calculateNextRetry(backOff);
      log.info(
          "Workflow {} will retry after {} minute(s). Attempt: {}",
          workflow.getWorkflowName(),
          retryDelay,
          context.getAttemptCount()
      );
    }

    context.setStatus(WorkflowStatus.STOPPED);
    context.setUpdatedAt(clock.instant());
    saveContext(context);
    throw new WorkflowStopExecutionException("Workflow execution exceeded the maximum retry time limit.");
  }

  /**
   * Executes the given workflow with the provided context.
   *
   * <p>Failures become a result status instead of propagating: a retryable failure yields
   * {@link WorkflowStatus#RETRYABLE_FAILURE}, a stop yields {@link WorkflowStatus#STOPPED} and any
   * other runtime failure yields {@link WorkflowStatus#FAILED}. The context is saved either way.
   *
   * @param workflow the workflow to be executed
   * @param context the context for the workflow execution
   */
  @Override
  public WorkflowResult executeWorkflow(Workflow workflow, WorkflowContext context) {
    var workflowName = workflow.getWorkflowName();
    try {
      log.info("Starting execution of workflow: {}", workflowName);
      var result = workflow.execute(context);
      markCompleted(context);
      log.info("Successfully completed execution of workflow: {}", workflowName);
      return result;
    } catch (WorkflowRetryableException failure) {
      log.warn(
          "Retryable error occurred during workflow execution: {}. Retrying workflow: {}",
          failure.getMessage(),
          workflowName
      );
      context.setStatus(WorkflowStatus.RETRYABLE_FAILURE);
      return resultWithStatus(WorkflowStatus.RETRYABLE_FAILURE);
    } catch (WorkflowStopExecutionException stop) {
      log.error("Stopping workflow execution due to stop execution exception: {}", stop.getMessage());
      markStopped(stop, workflowName, context);
      return resultWithStatus(WorkflowStatus.STOPPED);
    } catch (RuntimeException failure) {
      log.error(
          "Stopping workflow: Unexpected error occurred during workflow execution: {}",
          failure.getMessage(),
          failure
      );
      context.setStatus(WorkflowStatus.FAILED);
      return resultWithStatus(WorkflowStatus.FAILED);
    } finally {
      saveContext(context);
    }
  }

  public void stopWorkflowExecution(String workflowName) {
    log.info("Stop requested for workflow: {}", workflowName);
  }

  public void monitorWorkflowStatus(String workflowName) {
    log.info("Monitoring workflow status for workflow: {}", workflowName);
  }

  public void notifyWorkflowCompletion(String workflowName) {
    log.info("Workflow completed: {}", workflowName);
  }

  private WorkflowResult resultWithStatus(WorkflowStatus status) {
    var now = clock.instant();
    return WorkflowResult.builder()
        .id(UUID.randomUUID())
        .createdAt(now)
        .updatedAt(now)
        .status(status)
        .build();
  }

  private void markCompleted(WorkflowContext context) {
    context.setStatus(WorkflowStatus.COMPLETED);
    context.setUpdatedAt(clock.instant());
  }

  private void markStopped(WorkflowStopExecutionException stop, String workflowName, WorkflowContext context) {
    log.error("Handling workflow stop execution exception for workflow: {} with context: {}", workflowName, context, stop);
    context.setStatus(WorkflowStatus.STOPPED);
    context.setUpdatedAt(clock.instant());
  }

  /**
   * Records the current state of the workflow context. Contexts are logged, not persisted.
   *
   * @param context the workflow context to record
   */
  private void saveContext(WorkflowContext context) {
    if (context.getUpdatedAt() == null) {
      context.setUpdatedAt(clock.instant());
    }
    log.info("Saving workflow context: {}", context);
  }
}

package dev.christopherbell.whatsforlunch.workflow.engine.exception;

/**
 * Base exception class for workflow-related exceptions.
 */
public sealed class WorkflowException extends RuntimeException
    permits WorkflowRetryableException, WorkflowStopExecutionException {

  /**
   * Constructs a new WorkflowException with the specified detail message.
   *
   * @param message the detail message
   */
  public WorkflowException(String message) {
    super(message);
  }

  /**
   * Constructs a new WorkflowException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause   the cause of the exception
   */
  public WorkflowException(String message, Throwable cause) {
    super(message, cause);
  }
}

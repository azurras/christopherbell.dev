package dev.christopherbell.whatsforlunch.workflow.engine.exception;

/**
 * Exception indicating that a workflow execution should be retried.
 */
public final class WorkflowRetryableException extends WorkflowException {

  /**
   * Constructs a new WorkflowRetryableException with the specified detail message.
   *
   * @param message the detail message
   */
  public WorkflowRetryableException(String message) {
    super(message);
  }

  /**
   * Constructs a new WorkflowRetryableException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause   the cause of the exception
   */
  public WorkflowRetryableException(String message, Throwable cause) {
    super(message, cause);
  }
}

package dev.christopherbell.whatsforlunch.workflow.engine.exception;

/**
 * Exception indicating that a workflow execution should be stopped.
 */
public final class WorkflowStopExecutionException extends WorkflowException {

  /**
   * Constructs a new WorkflowStopExecutionException with the specified detail message.
   *
   * @param message the detail message
   */
  public WorkflowStopExecutionException(String message) {
    super(message);
  }

  /**
   * Constructs a new WorkflowStopExecutionException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause   the cause of the exception
   */
  public WorkflowStopExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}

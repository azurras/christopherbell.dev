package dev.christopherbell.whatsforlunch.workflow.engine.model;

/**
 * Enum representing the status of a workflow.
 */
public enum WorkflowStatus {
  /**
   * Indicates that the workflow was aborted.
   */
  ABORTED,
  /**
   * Indicates that the workflow was completed successfully.
   */
  COMPLETED,
  /**
   * Indicates that the workflow failed.
   */
  FAILED,
  /**
   * Indicates that the workflow is currently in progress.
   */
  IN_PROGRESS,
  /**
   * Indicates that the workflow is pending and has not yet started.
   */
  PENDING,
  /**
   * Indicates that the workflow encountered a retryable failure.
   */
  RETRYABLE_FAILURE,
  /**
   * Indicates that the workflow execution was stopped.
   */
  STOPPED,
}

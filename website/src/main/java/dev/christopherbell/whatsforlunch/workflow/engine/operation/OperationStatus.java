package dev.christopherbell.whatsforlunch.workflow.engine.operation;

/**
 * Enum representing the status of an operation within a workflow.
 */
public enum OperationStatus {
  /**
   * Indicates that the operation has completed successfully.
   */
  COMPLETED,
  /**
   * Indicates that the operation has failed during execution.
   */
  FAILED,
  /**
   * Indicates that the operation is currently in progress.
   */
  IN_PROGRESS,
}

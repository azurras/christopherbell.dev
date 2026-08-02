package dev.christopherbell.whatsforlunch.workflow.engine.model;

/**
 * Represents a type of workflow within the system. Enums should
 * implement this interface to define specific workflow types.
 */
public interface WorkflowType {

  /**
   * Returns the type of the workflow.
   *
   * @return the workflow type as a string
   */
  String getType();
}

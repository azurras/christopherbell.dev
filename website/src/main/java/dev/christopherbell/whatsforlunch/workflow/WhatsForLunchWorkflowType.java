package dev.christopherbell.whatsforlunch.workflow;

import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowType;

/**
 * Enum representing the different workflow types for the WhatsForLunch application.
 */
public enum WhatsForLunchWorkflowType implements WorkflowType {

  /**
   * Workflow type for the WhatsForLunch workflow.
   */
  WHATS_FOR_LUNCH_WORKFLOW;

  /**
   * Returns the name of the workflow type.
   *
   * @return the name of the workflow type
   */
  @Override
  public String getType() {
    return WHATS_FOR_LUNCH_WORKFLOW.name();
  }
}

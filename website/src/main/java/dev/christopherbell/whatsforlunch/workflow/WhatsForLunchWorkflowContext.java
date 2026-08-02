package dev.christopherbell.whatsforlunch.workflow;

import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowContext;
import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowType;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Context for the WhatsForLunch workflow, containing information about
 * the current time, selected restaurant, and reset time.
 */
@AllArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class WhatsForLunchWorkflowContext extends WorkflowContext {
  private Instant currentTime;
  private Restaurant restaurant;
  private Instant resetTime;

  @Override
  public WorkflowType getType() {
    return WhatsForLunchWorkflowType.WHATS_FOR_LUNCH_WORKFLOW;
  }
}

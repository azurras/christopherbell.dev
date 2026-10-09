package dev.christopherbell.whatsforlunch.workflow;

import dev.christopherbell.whatsforlunch.workflow.engine.Workflow;
import dev.christopherbell.whatsforlunch.workflow.engine.exception.WorkflowException;
import dev.christopherbell.whatsforlunch.workflow.engine.exception.WorkflowStopExecutionException;
import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowContext;
import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowResult;
import dev.christopherbell.whatsforlunch.workflow.engine.model.WorkflowStatus;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Workflow implementation for determining what's for lunch.
 */
@Component
public class WhatsForLunchWorkflow implements Workflow {

  private final Clock clock;

  /**
   * Creates the workflow with the clock that stamps its results.
   *
   * @param clock the application clock
   */
  public WhatsForLunchWorkflow(Clock clock) {
    this.clock = clock;
  }

  @Override
  public WorkflowResult execute(WorkflowContext context) throws WorkflowException {
    if (!(context instanceof WhatsForLunchWorkflowContext)) {
      throw new WorkflowStopExecutionException(
          "Invalid context type provided to WhatsForLunchWorkflow. Expected WhatsForLunchWorkflowContext."
      );
    }

    var now = clock.instant();
    return WhatsForLunchWorkflowResult.builder()
        .createdAt(now)
        .updatedAt(now)
        .id(UUID.randomUUID())
        .status(WorkflowStatus.COMPLETED)
        .build();
  }
}

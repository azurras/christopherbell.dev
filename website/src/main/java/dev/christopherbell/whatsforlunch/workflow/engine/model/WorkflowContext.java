package dev.christopherbell.whatsforlunch.workflow.engine.model;

import dev.christopherbell.whatsforlunch.workflow.engine.operation.OperationStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Represents the context of a workflow, containing information about its type, status, and operation history.
 */
@AllArgsConstructor
@Data
@NoArgsConstructor
@SuperBuilder
public class WorkflowContext {
  /**
   * The type of the workflow, used to identify the specific workflow implementation.
   */
  private WorkflowType type;

  /**
   * The unique identifier for this workflow instance.
   */
  private UUID id;

  /**
   * The number of attempts made to execute this workflow.
   */
  private int attemptCount;

  /**
   * The timestamp when this workflow instance was created.
   */
  private Instant createdAt;

  /**
   * A map containing the history of operations executed within this workflow, keyed by operation name.
   */
  private Map<String, OperationStatus> operationHistory;

  /**
   * The current status of the workflow, indicating its state in the execution process.
   */
  private WorkflowStatus status;

  /**
   * The timestamp when this workflow instance was last updated.
   */
  private Instant updatedAt;
}

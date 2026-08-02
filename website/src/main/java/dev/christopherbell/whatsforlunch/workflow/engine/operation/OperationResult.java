package dev.christopherbell.whatsforlunch.workflow.engine.operation;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the result of an operation within a workflow, including its status and timestamps.
 */
@AllArgsConstructor
@Builder
@Data
@NoArgsConstructor
public class OperationResult {
  /**
   * The unique identifier for this operation result instance.
   */
  private UUID id;

  /**
   * The status of the operation, indicating whether it completed successfully, failed, or is in progress.
   */
  private OperationStatus status;

  /**
   * The timestamp when this operation result instance was created.
   */
  private Instant createdAt;

  /**
   * The timestamp when this operation result instance was last updated.
   */
  private Instant updatedAt;
}

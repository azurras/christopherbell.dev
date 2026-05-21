package dev.christopherbell.whatsforlunch.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.christopherbell.libs.workflow.exception.WorkflowStopExecutionException;
import dev.christopherbell.libs.workflow.model.WorkflowContext;
import dev.christopherbell.libs.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WhatsForLunchWorkflowTest {

  @Test
  @DisplayName("Execute completes with timestamps and id for valid WFL context")
  void execute_whenContextValid_returnsCompletedResult() throws Exception {
    var result = new WhatsForLunchWorkflow().execute(new WhatsForLunchWorkflowContext());

    assertNotNull(result.getId());
    assertEquals(WorkflowStatus.COMPLETED, result.getStatus());
    assertNotNull(result.getCreatedAt());
    assertNotNull(result.getUpdatedAt());
    assertEquals(result.getCreatedAt(), result.getUpdatedAt());
  }

  @Test
  @DisplayName("Execute stops when context has the wrong type")
  void execute_whenContextInvalid_throwsStopExecutionException() {
    assertThrows(
        WorkflowStopExecutionException.class,
        () -> new WhatsForLunchWorkflow().execute(new WorkflowContext()));
  }
}

package dev.christopherbell.admin.commandcenter.action;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/** Minimal fixed-key Mongo representation of a pending machine power action. */
@Data
@NoArgsConstructor
final class PendingActionDocument {
  static final String ID = "machine-power";

  @Id private String id;
  private CommandCenterActionType action;
  private Instant acceptedAt;
  private Instant executeAt;
}

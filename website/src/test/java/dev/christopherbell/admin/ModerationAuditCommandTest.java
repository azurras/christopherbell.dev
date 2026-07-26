package dev.christopherbell.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.christopherbell.admin.activity.ModerationAuditCommand;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModerationAuditCommandTest {
  @Test
  @DisplayName("Moderation audit accepts allowlisted bounded state")
  void constructor_acceptsBoundedState() throws Exception {
    var command = ModerationAuditCommand.create(
        "ACCOUNT_ROLE_CHANGED", "ACCOUNT", "account-1", "@reader", "policy review",
        "%s changed an account role.",
        Map.of("role", "USER", "status", "ACTIVE"),
        Map.of("role", "MOD", "status", "ACTIVE"),
        Map.of("source", "back-office"));

    assertThat(command.reason()).isEqualTo("policy review");
    assertThat(command.beforeValues()).containsEntry("role", "USER");
  }

  @Test
  @DisplayName("Moderation audit rejects missing or oversized reasons")
  void constructor_rejectsInvalidReason() {
    assertThrows(InvalidRequestException.class, () -> command(" ", Map.of("status", "ACTIVE")));
    assertThrows(InvalidRequestException.class, () -> command("x".repeat(501), Map.of("status", "ACTIVE")));
  }

  @Test
  @DisplayName("Moderation audit rejects forbidden or oversized state partitions")
  void constructor_rejectsForbiddenState() {
    assertThrows(InvalidRequestException.class, () -> command(
        "reason", Map.of("email", "private@example.com")));
    assertThrows(InvalidRequestException.class, () -> command(
        "reason", Map.of("status", "x".repeat(101))));
  }

  private ModerationAuditCommand command(String reason, Map<String, String> after) throws Exception {
    return ModerationAuditCommand.create(
        "ACCOUNT_STATUS_CHANGED", "ACCOUNT", "account-1", "@reader", reason,
        "%s changed an account status.", Map.of("status", "INACTIVE"), after, Map.of());
  }
}

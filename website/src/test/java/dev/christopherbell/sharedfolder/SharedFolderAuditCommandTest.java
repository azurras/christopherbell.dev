package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.audit.SharedFolderAuditCommand;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SharedFolderAuditCommandTest {
  @Test
  void acceptsOnlyTheBoundedSafeAuditShape() {
    Instant occurredAt = Instant.parse("2026-07-17T12:00:00Z");

    var command = new SharedFolderAuditCommand(
        "account-1", "DOWNLOAD_STARTED", "music/song.mp3", occurredAt,
        "203.0.113.8", 42L, "accepted", null);

    assertThat(command.accountId()).isEqualTo("account-1");
    assertThat(command.action()).isEqualTo("DOWNLOAD_STARTED");
    assertThat(command.relativePathOrResourceId()).isEqualTo("music/song.mp3");
    assertThat(command.occurredAt()).isEqualTo(occurredAt);
    assertThat(command.clientIp()).isEqualTo("203.0.113.8");
    assertThat(command.size()).isEqualTo(42L);
    assertThat(command.outcome()).isEqualTo("accepted");
    assertThat(command.failureCategory()).isNull();
  }

  @Test
  void rejectsAbsolutePathsControlCharactersNegativeSizesAndUnboundedCategories() {
    Instant occurredAt = Instant.parse("2026-07-17T12:00:00Z");

    assertThatThrownBy(() -> new SharedFolderAuditCommand(
        "account-1", "DOWNLOAD_STARTED", "A:/Shared/secret.txt", occurredAt,
        "203.0.113.8", 42L, "accepted", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderAuditCommand(
        "account-1", "DOWNLOAD\nSTARTED", "music/song.mp3", occurredAt,
        "203.0.113.8", 42L, "accepted", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderAuditCommand(
        "account-1", "DOWNLOAD_STARTED", "music/song.mp3", occurredAt,
        "203.0.113.8", -1L, "accepted", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderAuditCommand(
        "account-1", "DOWNLOAD_STARTED", "music/song.mp3", occurredAt,
        "203.0.113.8", 42L, "rejected", "x".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsC1ControlCharactersInBoundedPathAndClientIpFields() {
    Instant occurredAt = Instant.parse("2026-07-17T12:00:00Z");

    assertThatThrownBy(() -> new SharedFolderAuditCommand(
        "account-1", "DOWNLOAD_STARTED", "music" + '\u0085' + "/song.mp3", occurredAt,
        "203.0.113.8", 42L, "accepted", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderAuditCommand(
        "account-1", "DOWNLOAD_STARTED", "music/song.mp3", occurredAt,
        "203.0.113." + '\u009f', 42L, "accepted", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

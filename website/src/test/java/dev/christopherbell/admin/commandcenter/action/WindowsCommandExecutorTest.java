package dev.christopherbell.admin.commandcenter.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowsCommandExecutorTest {

  private final CommandCenterProperties properties = properties();
  private final WindowsCommandExecutor executor = new WindowsCommandExecutor(properties);

  @Test
  void commandForUsesOnlyFixedAllowlistedArguments() {
    assertThat(executor.commandFor(CommandCenterActionType.RESTART_SITE))
        .containsExactly("C:\\service\\ChristopherBellDev.exe", "restart");
    assertThat(executor.commandFor(CommandCenterActionType.RESTART_COMPUTER))
        .containsExactly(
            "C:\\Windows\\System32\\shutdown.exe", "/r", "/t", "60", "/d", "p:0:0",
            "/c", "christopherbell.dev admin command center");
    assertThat(executor.commandFor(CommandCenterActionType.SHUTDOWN_COMPUTER))
        .containsExactly(
            "C:\\Windows\\System32\\shutdown.exe", "/s", "/t", "60", "/d", "p:0:0",
            "/c", "christopherbell.dev admin command center");
    assertThat(executor.commandFor(CommandCenterActionType.CANCEL_PENDING_ACTION))
        .containsExactly("C:\\Windows\\System32\\shutdown.exe", "/a");
  }

  @Test
  void powerDelayOverrideCannotChangeTheLiteralSixtySecondAllowlist() {
    properties.getActions().setPowerDelay(Duration.ofSeconds(5));

    assertThat(executor.commandFor(CommandCenterActionType.RESTART_COMPUTER))
        .containsSubsequence("/t", "60");
    assertThat(executor.commandFor(CommandCenterActionType.SHUTDOWN_COMPUTER))
        .containsSubsequence("/t", "60");
  }

  @Test
  void actionTypeIsAClosedSetWithExactConfirmationPhrases() {
    assertThat(CommandCenterActionType.values())
        .containsExactly(
            CommandCenterActionType.RESTART_SITE,
            CommandCenterActionType.RESTART_COMPUTER,
            CommandCenterActionType.SHUTDOWN_COMPUTER,
            CommandCenterActionType.CANCEL_PENDING_ACTION);
    assertThat(CommandCenterActionType.RESTART_SITE.getConfirmationPhrase()).isEqualTo("RESTART SITE");
    assertThat(CommandCenterActionType.RESTART_COMPUTER.getConfirmationPhrase())
        .isEqualTo("RESTART COMPUTER");
    assertThat(CommandCenterActionType.SHUTDOWN_COMPUTER.getConfirmationPhrase())
        .isEqualTo("SHUTDOWN COMPUTER");
    assertThat(CommandCenterActionType.CANCEL_PENDING_ACTION.getConfirmationPhrase()).isEmpty();
  }

  @Test
  void realExecutionFailsClosedOnNonWindowsHosts() {
    var nonWindowsExecutor = new WindowsCommandExecutor(properties, () -> false);

    assertThatThrownBy(() -> nonWindowsExecutor.execute(CommandCenterActionType.RESTART_SITE))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("Windows");
  }

  @Test
  void cancellationWaitsForAZeroExitWithinTheBoundedTimeout() throws Exception {
    var commands = new ArrayList<List<String>>();
    var timeouts = new ArrayList<Duration>();
    var cancelExecutor = new WindowsCommandExecutor(
        properties,
        () -> true,
        (command, timeout) -> {
          commands.add(command);
          timeouts.add(timeout);
          return new WindowsCommandExecutor.CommandResult(true, 0);
        });

    cancelExecutor.execute(CommandCenterActionType.CANCEL_PENDING_ACTION);

    assertThat(commands).containsExactly(
        List.of("C:\\Windows\\System32\\shutdown.exe", "/a"));
    assertThat(timeouts).containsExactly(Duration.ofSeconds(5));
  }

  @Test
  void cancellationRejectsNonZeroExitAndTimeout() {
    var nonZeroExecutor = new WindowsCommandExecutor(
        properties,
        () -> true,
        (command, timeout) -> new WindowsCommandExecutor.CommandResult(true, 5));
    var timedOutExecutor = new WindowsCommandExecutor(
        properties,
        () -> true,
        (command, timeout) -> new WindowsCommandExecutor.CommandResult(false, -1));

    assertThatThrownBy(() -> nonZeroExecutor.execute(
        CommandCenterActionType.CANCEL_PENDING_ACTION))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("exit code 5");
    assertThatThrownBy(() -> timedOutExecutor.execute(
        CommandCenterActionType.CANCEL_PENDING_ACTION))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("timed out");
  }

  private static CommandCenterProperties properties() {
    var properties = new CommandCenterProperties();
    properties.getActions().setWinSwExecutable(Path.of("C:\\service\\ChristopherBellDev.exe"));
    properties.getActions().setShutdownExecutable(Path.of("C:\\Windows\\System32\\shutdown.exe"));
    properties.getActions().setPowerDelay(Duration.ofSeconds(60));
    return properties;
  }
}

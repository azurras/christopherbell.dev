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
  void configuredPowerDelayIsTheOnlyVariableFixedArgument() {
    properties.getActions().setPowerDelay(Duration.ofSeconds(37));

    assertThat(executor.commandFor(CommandCenterActionType.RESTART_COMPUTER))
        .containsSubsequence("/t", "37");
    assertThat(executor.commandFor(CommandCenterActionType.SHUTDOWN_COMPUTER))
        .containsSubsequence("/t", "37");
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
  void everyActionUsesTheConfiguredResultTimeout() throws Exception {
    var commands = new ArrayList<List<String>>();
    var timeouts = new ArrayList<Duration>();
    properties.getActions().setCommandResultTimeout(Duration.ofSeconds(3));
    var boundedExecutor = new WindowsCommandExecutor(
        properties,
        () -> true,
        (command, timeout) -> {
          commands.add(command);
          timeouts.add(timeout);
          return new WindowsCommandExecutor.CommandResult(true, 0);
        });

    for (var action : CommandCenterActionType.values()) {
      boundedExecutor.execute(action);
    }

    assertThat(commands).hasSize(CommandCenterActionType.values().length);
    assertThat(timeouts).containsExactly(
        Duration.ofSeconds(3), Duration.ofSeconds(3),
        Duration.ofSeconds(3), Duration.ofSeconds(3));
  }

  @Test
  void everyActionRejectsNonZeroExitAndTimeout() {
    var nonZeroExecutor = new WindowsCommandExecutor(
        properties,
        () -> true,
        (command, timeout) -> new WindowsCommandExecutor.CommandResult(true, 5));
    var timedOutExecutor = new WindowsCommandExecutor(
        properties,
        () -> true,
        (command, timeout) -> new WindowsCommandExecutor.CommandResult(false, -1));

    assertThatThrownBy(() -> nonZeroExecutor.execute(
        CommandCenterActionType.RESTART_SITE))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("exit code 5");
    assertThatThrownBy(() -> timedOutExecutor.execute(
        CommandCenterActionType.RESTART_COMPUTER))
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

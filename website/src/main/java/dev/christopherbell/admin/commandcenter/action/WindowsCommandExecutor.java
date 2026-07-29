package dev.christopherbell.admin.commandcenter.action;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Launches fixed Windows commands selected exclusively by an allowlisted enum. */
public class WindowsCommandExecutor implements CommandExecutor {
  private final CommandCenterProperties properties;
  private final BooleanSupplier windowsHost;
  private final CommandRunner commandRunner;

  public WindowsCommandExecutor(CommandCenterProperties properties) {
    this(properties, WindowsCommandExecutor::isWindowsHost, WindowsCommandExecutor::runCommand);
  }

  WindowsCommandExecutor(CommandCenterProperties properties, BooleanSupplier windowsHost) {
    this(properties, windowsHost, WindowsCommandExecutor::runCommand);
  }

  WindowsCommandExecutor(
      CommandCenterProperties properties,
      BooleanSupplier windowsHost,
      CommandRunner commandRunner) {
    this.properties = properties;
    this.windowsHost = windowsHost;
    this.commandRunner = commandRunner;
  }

  @Override
  public void execute(CommandCenterActionType action) throws IOException {
    if (!windowsHost.getAsBoolean()) {
      throw new IOException("Fixed host actions require a Windows host.");
    }
    var timeout = properties.getActions().getCommandResultTimeout();
    var result = commandRunner.run(commandFor(action), timeout);
    if (!result.completed()) {
      throw new IOException("Fixed Windows action timed out.");
    }
    if (result.exitCode() != 0) {
      throw new IOException(
          "Fixed Windows action failed with exit code " + result.exitCode() + ".");
    }
  }

  /** Returns the exact argument array for package-level security tests. */
  List<String> commandFor(CommandCenterActionType action) {
    var actions = properties.getActions();
    return switch (action) {
      case RESTART_SITE -> List.of(actions.getWinSwExecutable().toString(), "restart");
      case RESTART_COMPUTER -> List.of(
          actions.getShutdownExecutable().toString(), "/r", "/t", powerDelaySeconds(actions),
          "/d", "p:0:0", "/c", "christopherbell.dev admin command center");
      case SHUTDOWN_COMPUTER -> List.of(
          actions.getShutdownExecutable().toString(), "/s", "/t", powerDelaySeconds(actions),
          "/d", "p:0:0", "/c", "christopherbell.dev admin command center");
      case CANCEL_PENDING_ACTION ->
          List.of(actions.getShutdownExecutable().toString(), "/a");
    };
  }

  private static String powerDelaySeconds(CommandCenterProperties.Actions actions) {
    return Long.toString(actions.getPowerDelay().toSeconds());
  }

  private static boolean isWindowsHost() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
        .startsWith("windows");
  }

  private static CommandResult runCommand(List<String> command, java.time.Duration timeout)
      throws IOException {
    var process = new ProcessBuilder(command).start();
    try {
      if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new CommandResult(false, -1);
      }
      return new CommandResult(true, process.exitValue());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for fixed Windows action.", exception);
    }
  }

  @FunctionalInterface
  interface CommandRunner {
    CommandResult run(List<String> command, java.time.Duration timeout) throws IOException;
  }

  record CommandResult(boolean completed, int exitCode) {}
}

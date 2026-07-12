package dev.christopherbell.admin.commandcenter.action;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Launches fixed Windows commands selected exclusively by an allowlisted enum. */
public class WindowsCommandExecutor implements CommandExecutor {
  private final CommandCenterProperties properties;
  private final BooleanSupplier windowsHost;

  public WindowsCommandExecutor(CommandCenterProperties properties) {
    this(properties, WindowsCommandExecutor::isWindowsHost);
  }

  WindowsCommandExecutor(CommandCenterProperties properties, BooleanSupplier windowsHost) {
    this.properties = properties;
    this.windowsHost = windowsHost;
  }

  @Override
  public void execute(CommandCenterActionType action) throws IOException {
    if (!windowsHost.getAsBoolean()) {
      throw new IOException("Fixed host actions require a Windows host.");
    }
    new ProcessBuilder(commandFor(action)).start();
  }

  /** Returns the exact argument array for package-level security tests. */
  List<String> commandFor(CommandCenterActionType action) {
    var actions = properties.getActions();
    var delay = Long.toString(actions.getPowerDelay().toSeconds());
    return switch (action) {
      case RESTART_SITE -> List.of(actions.getWinSwExecutable().toString(), "restart");
      case RESTART_COMPUTER -> List.of(
          actions.getShutdownExecutable().toString(), "/r", "/t", delay,
          "/d", "p:0:0", "/c", "christopherbell.dev admin command center");
      case SHUTDOWN_COMPUTER -> List.of(
          actions.getShutdownExecutable().toString(), "/s", "/t", delay,
          "/d", "p:0:0", "/c", "christopherbell.dev admin command center");
      case CANCEL_PENDING_ACTION ->
          List.of(actions.getShutdownExecutable().toString(), "/a");
    };
  }

  private static boolean isWindowsHost() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
        .startsWith("windows");
  }
}

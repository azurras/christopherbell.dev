package dev.christopherbell.admin.commandcenter.action;

import java.io.IOException;

/** Executes one action from the closed command-center allowlist. */
@FunctionalInterface
public interface CommandExecutor {
  void execute(CommandCenterActionType action) throws IOException;
}
